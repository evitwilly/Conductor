package com.bluelinelabs.conductor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.annotation.UiThread
import com.bluelinelabs.conductor.changehandler.SimpleSwapChangeHandler
import com.bluelinelabs.conductor.internal.NoOpControllerChangeHandler
import com.bluelinelabs.conductor.internal.TransactionIndexer
import com.bluelinelabs.conductor.internal.ensureMainThread
import com.bluelinelabs.conductor.internal.parcelableCompat

private const val TAG = "Conductor"
private const val KEY_BACKSTACK = "Router.backstack"
private const val KEY_POP_ROOT_CONTROLLER_MODE = "Router.popRootControllerMode"
private const val KEY_ON_BACK_PRESSED_DISPATCHER_ENABLED = "Router.onBackPressedDispatcherEnabled"

abstract class Router2 {

  private val backstack = Backstack()

  private val changeListeners = mutableListOf<ControllerChangeHandler.ControllerChangeListener>()
  private val pendingControllerChanges = mutableListOf<ControllerChangeHandler.ChangeTransaction>()
  private val destroyingControllers = mutableListOf<Controller>()

  private var popRootControllerMode = PopRootControllerMode.NEVER

  private var onBackPressedDispatcherEnabled = false
  private var containerFullyAttached = false
  private var isActivityStopped = false

  private var container: ViewGroup? = null

  init {
    backstack.onBackstackUpdatedListener = Backstack.OnBackstackUpdatedListener {
      if (!onBackPressedDispatcherEnabled) {
        return@OnBackstackUpdatedListener
      }

      backstack.forEachIndexed { index, routerTransaction ->
        val onBackPressedCallbackEnabled = index > 0 || popRootControllerMode != PopRootControllerMode.NEVER
        routerTransaction.controller.onBackPressedCallback.isEnabled = onBackPressedCallbackEnabled
      }
    }
  }

  /**
   * Returns this Router's host Activity or {@code null} if it has either not yet been attached to
   * an Activity or if the Activity has been destroyed.
   */
  abstract fun activity(): Activity?

  /**
   * This should be called by the host Activity when its onActivityResult method is called if the instanceId
   * of the controller that called startActivityForResult is not known.
   *
   * @param requestCode The Activity's onActivityResult requestCode
   * @param resultCode  The Activity's onActivityResult resultCode
   * @param data        The Activity's onActivityResult data
   */
  abstract fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)

  /**
   * Returns the OnBackPressedDispatcher for this Router's host Activity or {@code null} if:
   *   - This Router has not yet been attached to an Activity
   *   - The attached Activity does not extend ComponentActivity
   *   - The Activity has been destroyed
   */
  fun getOnBackPressedDispatcher(): OnBackPressedDispatcher? {
    val activity = activity()
    if (activity is ComponentActivity) {
      return activity.onBackPressedDispatcher
    }
    return null
  }

  /**
   * This should be called by the host Activity when its onRequestPermissionsResult method is called. The call will be forwarded
   * to the {@link Controller} with the instanceId passed in.
   *
   * @param instanceId   The instanceId of the Controller to which this result should be forwarded
   * @param requestCode  The Activity's onRequestPermissionsResult requestCode
   * @param permissions  The Activity's onRequestPermissionsResult permissions
   * @param grantResults The Activity's onRequestPermissionsResult grantResults
   */
  fun onRequestPermissionsResult(instanceId: String, requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
    val controller = getControllerWithInstanceId(instanceId)
    controller?.requestPermissionsResult(requestCode, permissions, grantResults)
  }

  /**
   * This should be called by the host Activity when its onBackPressed method is called. The call will be forwarded
   * to its top {@link Controller}. If that controller doesn't handle it, then it will be popped.
   *
   * Note: This method has been deprecated and should be replaced with registering OnBackPressedCallbacks with
   * Controller instances.
   *
   * @return Whether or not a back action was handled by the Router
   */
  @UiThread
  @Deprecated(message = "")
  fun handleBack(): Boolean {
    ensureMainThread()

    return handleBackDispatch()
  }

  protected fun handleBackDispatch(): Boolean {
    if (!backstack.isEmpty) {
      //noinspection ConstantConditions
      if (backstack.peek()?.controller?.handleBack() == true) {
        return true
      }
      if ((backstack.size > 1 || popRootControllerMode != PopRootControllerMode.NEVER) && popCurrentController()) {
        return true
      }
    }

    return false
  }

  /**
   * Pops the top {@link Controller} from the backstack
   *
   * @return Whether or not this Router still has controllers remaining on it after popping.
   */
  @SuppressWarnings("WeakerAccess")
  @UiThread
  fun popCurrentController() : Boolean {
    ensureMainThread()

    val transaction = backstack.peek() ?: error("Trying to pop the current controller when there are none on the backstack.")
    return popController(transaction.controller)
  }

  /**
   * Pops the passed {@link Controller} from the backstack
   *
   * @param controller The controller that should be popped from this Router
   * @return Whether or not this Router still has controllers remaining on it after popping.
   */
  @UiThread
  fun popController(controller: Controller): Boolean {
    ensureMainThread()

    val topTransaction = backstack.peek()
    val poppingTopController = topTransaction != null && topTransaction.controller == controller

    if (poppingTopController) {
      trackDestroyingController(backstack.pop())
      performControllerChange(backstack.peek(), topTransaction, false)
    } else {
      var removedTransaction: RouterTransaction? = null
      var nextTransaction: RouterTransaction? = null
      val topPushHandler = topTransaction?.pushChangeHandler()
      val needsNextTransactionAttach = topPushHandler?.removesFromViewOnPush == false

      backstack.forEach { transaction ->
        if (transaction.controller == controller) {
          trackDestroyingController(transaction)
          backstack.remove(transaction)
          removedTransaction = transaction
        } else if (removedTransaction != null) {
          if (needsNextTransactionAttach && !transaction.controller.isAttached) {
            nextTransaction = transaction;
          }
          return@forEach
        }
      }

      if (removedTransaction != null) {
        performControllerChange(nextTransaction, removedTransaction, false)
      }
    }

    return if (popRootControllerMode == PopRootControllerMode.POP_ROOT_CONTROLLER_AND_VIEW) {
      topTransaction != null
    } else {
      !backstack.isEmpty
    }
  }

  /**
   * Pushes a new {@link Controller} to the backstack
   *
   * @param transaction The transaction detailing what should be pushed, including the {@link Controller},
   *                    and its push and pop {@link ControllerChangeHandler}, and its tag.
   */
  @UiThread
  fun pushController(transaction: RouterTransaction) {
    ensureMainThread()

    val from = backstack.peek()
    pushToBackstack(transaction)
    performControllerChange(transaction, from, true)
  }

  /**
   * Replaces this Router's top {@link Controller} with a new {@link Controller}
   *
   * @param transaction The transaction detailing what should be pushed, including the {@link Controller},
   *                    and its push and pop {@link ControllerChangeHandler}, and its tag.
   */
  @SuppressWarnings("WeakerAccess")
  @UiThread
  fun replaceTopController(transaction: RouterTransaction) {
    ensureMainThread()

    val topTransaction = backstack.peek()
    if (!backstack.isEmpty) {
      trackDestroyingController(backstack.pop())
    }

    val handler = transaction.pushChangeHandler()
    if (topTransaction != null) {
      val topTransactionPushControllerChangeHandler = topTransaction.pushChangeHandler()
      val oldHandlerRemovedViews = topTransactionPushControllerChangeHandler == null || topTransactionPushControllerChangeHandler.removesFromViewOnPush
      val newHandlerRemovesViews = handler == null || handler.removesFromViewOnPush
      if (!oldHandlerRemovedViews && newHandlerRemovesViews) {
        for (visibleTransaction in getVisibleTransactions(backstack.iterator(), true)) {
          performControllerChange(null, visibleTransaction, true, handler)
        }
      }
    }

    pushToBackstack(transaction)

    handler?.setForceRemoveViewOnPush(true)
    performControllerChange(transaction.pushChangeHandler(handler), topTransaction, true)
  }

  internal fun destroy(popViews: Boolean) {
    popRootControllerMode = PopRootControllerMode.POP_ROOT_CONTROLLER_AND_VIEW
    val poppedControllers = backstack.popAll()
    trackDestroyingControllers(poppedControllers)

    var topTransaction: RouterTransaction? = null
    if (popViews && poppedControllers.isNotEmpty()) {
      topTransaction = poppedControllers[0]
      topTransaction.controller.addLifecycleListener(object: Controller.LifecycleListener() {
        override fun onChangeEnd(controller: Controller, changeHandler: ControllerChangeHandler, changeType: ControllerChangeType) {
          if (changeType == ControllerChangeType.POP_EXIT) {
            var poppedControllersIndex = poppedControllers.size - 1
            while (poppedControllersIndex > 0) {
              val transaction = poppedControllers[poppedControllersIndex]
              performControllerChange(null, transaction, true, SimpleSwapChangeHandler())
              poppedControllersIndex--
            }
          }
        }
      })
    }

    if (poppedControllers.isNotEmpty()) {
      val changeHandler = NoOpControllerChangeHandler()
      for (routerTransaction in poppedControllers) {
        if (routerTransaction != topTransaction) {
          routerTransaction.controller.changeStarted(changeHandler, ControllerChangeType.POP_EXIT)
          routerTransaction.controller.changeEnded(changeHandler, ControllerChangeType.POP_EXIT)
        }
      }
    }
  }

  fun getContainerId(): Int {
    return container?.id ?: 0
  }

  /**
   * If set to true, this router will handle back presses by performing a change handler on the last controller and view
   * in the stack. This defaults to false so that the developer can either finish its containing Activity or otherwise
   * hide its parent view without any strange artifacting.
   */
  @Deprecated("This method has been deprecated and should be replaced with setPopRootControllerMode.")
  fun setPopsLastView(popsLastView: Boolean): Router2 {
    popRootControllerMode = if (popsLastView) {
      PopRootControllerMode.POP_ROOT_CONTROLLER_AND_VIEW
    } else {
      PopRootControllerMode.POP_ROOT_CONTROLLER_BUT_NOT_VIEW
    }
    return this
  }

  /**
   * Sets the method this router will use to handle back presses when there is only one controller left in the backstack.
   * Defaults to POP_ROOT_CONTROLLER_BUT_NOT_VIEW so that the developer can either finish its containing Activity or
   * otherwise hide its parent view without any strange artifacting.
   */
  fun setPopRootControllerMode(popRootControllerMode: PopRootControllerMode): Router2 {
    this.popRootControllerMode = popRootControllerMode
    return this
  }

  fun setOnBackPressedDispatcherEnabled(enabled: Boolean): Router2 {
    if (backstack.size > 0 && enabled != onBackPressedDispatcherEnabled) {
      Log.e(TAG, "setOnBackPressedDispatcherEnabled call ignored, as controllers with a different setting have already been pushed.")
    }
    onBackPressedDispatcherEnabled = enabled
    return this;
  }

  /**
   * Pops all {@link Controller}s until only the root is left
   *
   * @return Whether or not any {@link Controller}s were popped in order to get to the root transaction
   */
  @UiThread
  fun popToRoot(): Boolean {
    ensureMainThread()

    return popToRoot(null)
  }

  /**
   * Pops all {@link Controller} until only the root is left
   *
   * @param changeHandler The {@link ControllerChangeHandler} to handle this transaction
   * @return Whether or not any {@link Controller}s were popped in order to get to the root transaction
   */
  @SuppressWarnings("WeakerAccess")
  @UiThread
  fun popToRoot(changeHandler: ControllerChangeHandler?): Boolean {
    ensureMainThread()

    val root = backstack.root()
    if (root != null) {
      popToTransaction(root, changeHandler)
      return true
    }

    return false
  }

  /**
   * Pops all {@link Controller}s until the Controller with the passed tag is at the top
   *
   * @param tag The tag being popped to
   * @return Whether or not any {@link Controller}s were popped in order to get to the transaction with the passed tag
   */
  @UiThread
  fun popToTag(tag: String): Boolean {
    ensureMainThread()

    return popToTag(tag, null)
  }

  /**
   * Pops all {@link Controller}s until the {@link Controller} with the passed tag is at the top
   *
   * @param tag           The tag being popped to
   * @param changeHandler The {@link ControllerChangeHandler} to handle this transaction
   * @return Whether or not the {@link Controller} with the passed tag is now at the top
   */
  @SuppressWarnings("WeakerAccess")
  @UiThread
  fun popToTag(tag: String, changeHandler: ControllerChangeHandler?): Boolean {
    ensureMainThread()

    for (transaction in backstack) {
      if (tag == transaction.tag()) {
        popToTransaction(transaction, changeHandler)
        return true
      }
    }

    return false
  }

  /**
   * Sets the root Controller. If any {@link Controller}s are currently in the backstack, they will be removed.
   *
   * @param transaction The transaction detailing what should be pushed, including the {@link Controller},
   *                    and its push and pop {@link ControllerChangeHandler}, and its tag.
   */
  @UiThread
  fun setRoot(transaction: RouterTransaction) {
    ensureMainThread()

    val transactions = listOf(transaction)
    setBackstack(transactions, transaction.pushChangeHandler())
  }

  /**
   * Returns the hosted Controller with the given instance id or {@code null} if no such
   * Controller exists in this Router.
   *
   * @param instanceId The instance ID being searched for
   */
  fun getControllerWithInstanceId(instanceId: String): Controller? {
    for (transaction in backstack) {
      val controllerWithId = transaction.controller.findController(instanceId)
      if (controllerWithId != null) {
        return controllerWithId
      }
    }

    return null
  }

  /**
   * Returns the hosted Controller that was pushed with the given tag or {@code null} if no
   * such Controller exists in this Router.
   *
   * @param tag The tag being searched for
   */
  fun getControllerWithTag(tag: String): Controller? {
    for (transaction in backstack) {
      if (tag == transaction.tag()) {
        return transaction.controller
      }
    }
    return null
  }

  /**
   * Returns the number of {@link Controller}s currently in the backstack
   */
  @SuppressWarnings("WeakerAccess")
  fun getBackstackSize(): Int {
    return backstack.size
  }

  /**
   * Returns the current backstack, ordered from root to most recently pushed.
   */
  fun getBackstack(): List<RouterTransaction> {
    return backstack.reversed()
  }

  /**
   * Sets the backstack, transitioning from the current top controller to the top of the new stack (if different)
   * using the passed {@link ControllerChangeHandler}
   *
   * @param newBackstack  The new backstack
   * @param changeHandler An optional change handler to be used to handle the root view of transition
   */
  @SuppressWarnings("WeakerAccess")
  @UiThread
  fun setBackstack(newBackstack: List<RouterTransaction>, changeHandler: ControllerChangeHandler?) {
    ensureMainThread()

    val oldTransactions = getBackstack()
    val oldVisibleTransactions = getVisibleTransactions(backstack.iterator(), false)

    removeAllExceptVisibleAndUnowned()
    ensureOrderedTransactionIndices(newBackstack)
    ensureNoDuplicateControllers(newBackstack)

    backstack.setBackstack(newBackstack)

    val transactionsToBeRemoved = mutableListOf<RouterTransaction>()
    for (oldTransaction in oldTransactions) {
      var contains = false
      for (newTransaction in newBackstack) {
        if (oldTransaction.controller == newTransaction.controller) {
          contains = true
          break
        }
      }

      if (!contains) {
        // Inform the controller that it will be destroyed soon
        oldTransaction.controller.isBeingDestroyed = true
        transactionsToBeRemoved.add(oldTransaction)
      }

    }

    // Ensure all new controllers have a valid router set
    val backstackIterator = backstack.reverseIterator()
    while (backstackIterator.hasNext()) {
      val transaction = backstackIterator.next()
      transaction.onAttachedToRouter()
      setRouterOnController(transaction.controller)
    }

    if (newBackstack.isNotEmpty()) {
      val reverseNewBackstack = newBackstack.reversed()
      val newVisibleTransactions = getVisibleTransactions(reverseNewBackstack.iterator(), false)
      val newRootRequiresPush = !(newVisibleTransactions.isNotEmpty() && oldTransactions.contains(newVisibleTransactions[0]))

      val visibleTransactionsChanged = !backstacksAreEqual(newVisibleTransactions, oldVisibleTransactions)
      if (visibleTransactionsChanged) {
        val oldRootTransaction = if (oldVisibleTransactions.isNotEmpty()) oldVisibleTransactions[0] else null
        val newRootTransaction = newVisibleTransactions[0]

        if (oldRootTransaction == null || oldRootTransaction.controller !== newRootTransaction.controller) {
          // Ensure the existing root controller is fully pushed to the view hierarchy
          if (oldRootTransaction != null) {
            ControllerChangeHandler.completeHandlerImmediately(oldRootTransaction.controller.getInstanceId())
          }
          performControllerChange(newRootTransaction, oldRootTransaction, newRootRequiresPush, changeHandler)
        }

        // Remove all visible controllers that were previously on the backstack
        var oldVisibleTransactionsIndex = oldVisibleTransactions.size - 1
        while (oldVisibleTransactionsIndex > 0) {
          val transaction = oldVisibleTransactions[oldVisibleTransactionsIndex]
          if (!newVisibleTransactions.contains(transaction)) {
            val localHandler = changeHandler?.copy() ?: SimpleSwapChangeHandler()
            localHandler.setForceRemoveViewOnPush(true)
            ControllerChangeHandler.completeHandlerImmediately(transaction.controller.getInstanceId())

            if (transaction.controller.view != null) {
              performControllerChange(null, transaction, newRootRequiresPush, localHandler)
            }
          }
          oldVisibleTransactionsIndex--
        }

        var newVisibleTransactionsIndex = 1
        while (newVisibleTransactionsIndex < newVisibleTransactions.size) {
          val transaction = newVisibleTransactions[newVisibleTransactionsIndex]
          if (!oldVisibleTransactions.contains(transaction)) {
            performControllerChange(transaction, newVisibleTransactions[newVisibleTransactionsIndex - 1], true, transaction.pushChangeHandler())
          }
          newVisibleTransactionsIndex++
        }

      }
    } else {
      // Remove all visible controllers that were previously on the backstack
      var oldVisibleTransactionsIndex = oldVisibleTransactions.size - 1
      while (oldVisibleTransactionsIndex >= 0) {
        val transaction = oldVisibleTransactions[oldVisibleTransactionsIndex]
        val localHandler = changeHandler?.copy() ?: SimpleSwapChangeHandler()
        ControllerChangeHandler.completeHandlerImmediately(transaction.controller.getInstanceId())
        performControllerChange(null, transaction, false, localHandler)
        oldVisibleTransactionsIndex--
      }
    }

    for (removedTransaction in transactionsToBeRemoved) {
      var willBeRemoved = false
      for (pendingTransaction in pendingControllerChanges) {
        if (pendingTransaction.from == removedTransaction.controller) {
          willBeRemoved = true
        }
      }

      if (!willBeRemoved) {
        removedTransaction.controller.destroy()
      }
    }

  }

  /**
   * Returns whether or not this Router has a root {@link Controller}
   */
  fun hasRootController(): Boolean {
    return getBackstackSize() > 0
  }

  /**
   * Adds a listener for all of this Router's {@link Controller} change events
   *
   * @param changeListener The listener
   */
  @SuppressWarnings("WeakerAccess")
  fun addChangeListener(changeListener: ControllerChangeHandler.ControllerChangeListener) {
    if (!changeListeners.contains(changeListener)) {
      changeListeners.add(changeListener)
    }
  }

  /**
   * Removes a previously added listener
   *
   * @param changeListener The listener to be removed
   */
  @SuppressWarnings("WeakerAccess")
  fun removeChangeListener(changeListener: ControllerChangeHandler.ControllerChangeListener) {
    changeListeners.remove(changeListener)
  }

  /**
   * Attaches this Router's existing backstack to its container if one exists.
   */
  @UiThread
  fun rebindIfNeeded() {
    ensureMainThread()

    // Not directly using the iterator in order to prevent ConcurrentModificationExceptions if controllers pop
    // themselves on re-attach.
    for (transaction in getTransactions()) {
      if (transaction.controller.needsAttach) {
        performControllerChange(transaction, null, true, SimpleSwapChangeHandler(false))
      } else {
        setRouterOnController(transaction.controller)
      }
    }
  }

  fun onActivityResult(instanceId: String, requestCode: Int, resultCode: Int, data: Intent?) {
    val controller = getControllerWithInstanceId(instanceId)
    controller?.onActivityResult(requestCode, resultCode, data)
  }

  fun onActivityStarted(activity: Activity) {
    isActivityStopped = false

    for (transaction in backstack) {
      transaction.controller.activityStarted(activity)

      for (childRouter in transaction.controller.childRouters) {
        childRouter.onActivityStarted(activity)
      }
    }
  }

  fun onActivityResumed(activity: Activity) {
    for (transaction in backstack) {
      transaction.controller.activityResumed(activity)

      for (childRouter in transaction.controller.childRouters) {
        childRouter.onActivityResumed(activity)
      }
    }
  }

  fun onActivityPaused(activity: Activity) {
    for (transaction in backstack) {
      transaction.controller.activityPaused(activity)

      for (childRouter in transaction.controller.childRouters) {
        childRouter.onActivityPaused(activity)
      }
    }
  }

  fun onActivityStopped(activity: Activity) {
    for (transaction in backstack) {
      transaction.controller.activityStopped(activity)

      for (childRouter in transaction.controller.childRouters) {
        childRouter.onActivityStopped(activity)
      }
    }

    isActivityStopped = true
  }

  fun onActivityDestroyed(activity: Activity, isConfigurationChange: Boolean) {
    prepareForContainerRemoval()
    changeListeners.clear()

    for (transaction in backstack) {
      transaction.controller.activityDestroyed(activity)

      for (childRouter in transaction.controller.childRouters) {
        childRouter.onActivityDestroyed(activity, isConfigurationChange)
      }
    }

    var destroyingControllersIndex = destroyingControllers.size - 1
    while (destroyingControllersIndex >= 0) {
      val controller = destroyingControllers[destroyingControllersIndex]
      controller.activityDestroyed(activity)

      for (childRouter in controller.childRouters) {
        childRouter.onActivityDestroyed(activity, isConfigurationChange)
      }
      destroyingControllersIndex--
    }

    container = null
  }

  fun prepareForHostDetach() {
    pendingControllerChanges.clear()

    for (transaction in backstack) {
      if (ControllerChangeHandler.completeHandlerImmediately(transaction.controller.instanceId)) {
        transaction.controller.needsAttach = true
      }

      transaction.controller.prepareForHostDetach()
    }
  }

  fun saveInstanceState(outState: Bundle) {
    val backstackState = Bundle()
    backstack.saveInstanceState(backstackState)

    outState.putInt(KEY_POP_ROOT_CONTROLLER_MODE, popRootControllerMode.ordinal)
    outState.putBoolean(KEY_ON_BACK_PRESSED_DISPATCHER_ENABLED, onBackPressedDispatcherEnabled)
    outState.putParcelable(KEY_BACKSTACK, backstackState)
  }

  fun restoreInstanceState(savedInstanceState: Bundle) {
    val backstackBundle = savedInstanceState.parcelableCompat(KEY_BACKSTACK) ?: Bundle()
    //noinspection ConstantConditions
    popRootControllerMode = PopRootControllerMode.values()[savedInstanceState.getInt(KEY_POP_ROOT_CONTROLLER_MODE)]
    onBackPressedDispatcherEnabled = savedInstanceState.getBoolean(KEY_ON_BACK_PRESSED_DISPATCHER_ENABLED)
    backstack.restoreInstanceState(backstackBundle)

    backstack.reversed().forEach { routerTransaction ->
      setRouterOnController(routerTransaction.controller)
    }
  }

  fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
    for (transaction in backstack) {
      transaction.controller.createOptionsMenu(menu, inflater)

      for (childRouter in transaction.controller.childRouters) {
        childRouter.onCreateOptionsMenu(menu, inflater)
      }
    }
  }

  fun onPrepareOptionsMenu(menu: Menu) {
    for (transaction in backstack) {
      transaction.controller.prepareOptionsMenu(menu)

      for (childRouter in transaction.controller.childRouters) {
        childRouter.onPrepareOptionsMenu(menu)
      }
    }
  }

  fun onOptionsItemSelected(item: MenuItem): Boolean {
    for (transaction in backstack) {
      if (transaction.controller.optionsItemSelected(item)) {
        return true
      }

      for (childRouter in transaction.controller.childRouters) {
        if (childRouter.onOptionsItemSelected(item)) {
          return true
        }
      }
    }
    return false
  }

  private fun popToTransaction(transaction: RouterTransaction, changeHandler: ControllerChangeHandler?) {
    if (backstack.size > 0) {
      val topTransaction = backstack.peek()

      val updatedBackstack = mutableListOf<RouterTransaction>()

      backstack.reversed().forEach { existingTransaction ->
        updatedBackstack.add(existingTransaction)
        if (existingTransaction == transaction) {
          return@forEach
        }
      }

      val backstackChangeHandler = changeHandler ?: topTransaction?.popChangeHandler()

      setBackstack(updatedBackstack, backstackChangeHandler)
    }
  }

  internal fun watchContainerAttach() {
    container?.post {
      containerFullyAttached = true
      performPendingControllerChanges()
    }
  }

  internal fun prepareForContainerRemoval() {
    containerFullyAttached = false

    if (container != null) {
      container?.setOnHierarchyChangeListener(null)
    }
  }

  internal fun onContextAvailable() {
    backstack.forEach { routerTransaction ->
      routerTransaction.controller.onContextAvailable()
    }
  }

  internal fun onContextUnavailable(context: Context) {
    for (transaction in backstack) {
      transaction.controller.onContextUnavailable(context)
    }
    for (controller in destroyingControllers) {
      controller.onContextUnavailable(context)
    }
  }

  internal fun getControllers(): List<Controller> {
    return backstack.reversed().map { it.controller }
  }

  internal fun getTransactions(): List<RouterTransaction> {
    return backstack.reversed()
  }

  fun handleRequestedPermission(permission: String): Boolean? {
    for (transaction in backstack) {
      if (transaction.controller.didRequestPermission(permission)) {
        return transaction.controller.shouldShowRequestPermissionRationale(permission)
      }
    }
    return null
  }

  internal fun performControllerChange(to: RouterTransaction?, from: RouterTransaction?, isPush: Boolean) {
    if (isPush && to != null) {
      to.onAttachedToRouter()
    }

    val changeHandler = when {
      isPush -> to?.popChangeHandler()
      from != null -> from.popChangeHandler()
      else -> null
    }

    performControllerChange(to, from, isPush, changeHandler)
  }

  private fun performControllerChange(to: RouterTransaction?, from: RouterTransaction?, isPush: Boolean, changeHandler: ControllerChangeHandler?) {
    val toController = to?.controller
    val fromController = from?.controller
    var forceDetachDestroy = false

    var backstackChangeHandler = changeHandler

    if (to != null) {
      to.ensureValidIndex(getTransactionIndexer())
      if (toController != null) {
        setRouterOnController(toController)
      }
    } else if (backstack.size == 0 && popRootControllerMode == PopRootControllerMode.POP_ROOT_CONTROLLER_BUT_NOT_VIEW) {
      // We're emptying out the backstack. Views get weird if you transition them out, so just no-op it. The host
      // Activity or controller should be handling this by finishing or at least hiding this view.
      backstackChangeHandler = NoOpControllerChangeHandler()
      forceDetachDestroy = true
    } else if (!isPush && fromController != null && !fromController.isAttached) {
      // We're popping fromController from the middle of the backstack,
      // need to do it immediately and destroy the controller
      forceDetachDestroy = true
    }

    performControllerChange(toController, fromController, isPush, backstackChangeHandler)

    if (forceDetachDestroy && fromController != null) {
      if (fromController.getView() != null) {
        fromController.detach(fromController.getView(), true, false)
      } else {
        fromController.destroy()
      }
    }
  }

  private fun performControllerChange(to: Controller?, from: Controller?, isPush: Boolean, changeHandler: ControllerChangeHandler?) {
    if (isPush && to != null && to.isDestroyed) {
      error("Trying to push a controller that has already been destroyed. (" + to::class.java.simpleName + ")")
    }

    val transaction = ControllerChangeHandler.ChangeTransaction(to, from, isPush, container, changeHandler, changeListeners.toList())

    if (pendingControllerChanges.size > 0) {
      // If we already have changes queued up (awaiting full container attach), queue this one up as well so they don't happen
      // out of order.
      to?.needsAttach = true
      pendingControllerChanges.add(transaction)
    } else if (from != null && (changeHandler == null || changeHandler.removesFromViewOnPush) && !containerFullyAttached) {
      // If the change handler will remove the from view, we have to make sure the container is fully attached first so we avoid NPEs
      // within ViewGroup (details on issue #287). Post this to the container to ensure the attach is complete before we try to remove
      // anything.
      to?.needsAttach = true
      pendingControllerChanges.add(transaction)
      container?.post {
        performPendingControllerChanges()
      }
    } else {
      ControllerChangeHandler.executeChange(transaction)
    }
  }

  internal fun performPendingControllerChanges() {
    // We're intentionally using dynamic size checking (list.size()) here so we can account for changes
    // that occur during this loop (ex: if a controller is popped from within onAttach)
    pendingControllerChanges.forEach {
      ControllerChangeHandler.executeChange(it)
    }
    pendingControllerChanges.clear()
  }

  protected fun pushToBackstack(entry: RouterTransaction) {
    if (backstack.contains(entry.controller)) {
      error("Trying to push a controller that already exists on the backstack.")
    }
    backstack.push(entry)
  }

  private fun trackDestroyingController(transaction: RouterTransaction) {
    if (!transaction.controller.isDestroyed) {
      destroyingControllers.add(transaction.controller)

      transaction.controller.addLifecycleListener(object: Controller.LifecycleListener() {
        override fun postDestroy(controller: Controller) {
          destroyingControllers.remove(controller)
        }
      })
    }
  }

  private fun trackDestroyingControllers(transactions: List<RouterTransaction>) {
    for (transaction in transactions) {
      trackDestroyingController(transaction)
    }
  }

  private fun removeAllExceptVisibleAndUnowned() {
    val views = mutableListOf<View>()

    for (transaction in getVisibleTransactions(backstack.iterator(), false)) {
      val view = transaction.controller.getView()
      if (view != null) {
        views.add(view)
      }
    }

    for (router in getSiblingRouters()) {
      if (router.container == container) {
        addRouterViewsToList(router, views)
      }
    }

    val container = container ?: return

    var childIndex = container.childCount - 1
    while (childIndex >= 0) {
      val childView = container.getChildAt(childIndex)
      if (!views.contains(childView)) {
        container.removeView(childView)
      }
      childIndex--
    }
  }

  // Swap around transaction indices to ensure they don't get thrown out of order by the
  // developer rearranging the backstack at runtime.
  private fun ensureOrderedTransactionIndices(backstack: List<RouterTransaction>) {
    val indices = backstack.map { routerTransaction ->
      routerTransaction.ensureValidIndex(getTransactionIndexer())
      routerTransaction.transactionIndex
    }.sorted()

    var backstackIndex = 0
    while (backstackIndex < backstack.size) {
      backstack[backstackIndex].transactionIndex = indices[backstackIndex]
      backstackIndex++
    }
  }

  private fun ensureNoDuplicateControllers(backstack: List<RouterTransaction>) {
    var backstackIndex = 0
    while (backstackIndex < backstack.size) {
      val controller = backstack[backstackIndex].controller
      var backstackInnerIndex = backstackIndex + 1
      while (backstackInnerIndex < backstack.size) {
        if (backstack[backstackInnerIndex].controller == controller) {
          error("Trying to push the same controller to the backstack more than once.")
        }
        backstackInnerIndex++
      }
      backstackIndex++
    }
  }

  private fun addRouterViewsToList(router: Router , list: MutableList<View>) {
    for (controller in router.controllers) {
      val view = controller.getView()
      if (view != null) {
        list.add(view)
      }

      for (child in controller.childRouters) {
        addRouterViewsToList(child, list)
      }
    }
  }

  private fun getVisibleTransactions(backstackIterator: Iterator<RouterTransaction>, onlyTop: Boolean): List<RouterTransaction> {
    var visible = true

    val transactions = mutableListOf<RouterTransaction>()
    while (backstackIterator.hasNext()) {
      val transaction = backstackIterator.next()

      if (visible) {
        transactions.add(transaction)
      }

      visible = transaction.pushChangeHandler()?.removesFromViewOnPush == false

      if (onlyTop && !visible) {
        break
      }
    }

    return transactions.reversed()
  }

  private fun backstacksAreEqual(lhs: List<RouterTransaction>, rhs: List<RouterTransaction>): Boolean {
    if (lhs.size != rhs.size) {
      return false
    }

    var index = 0
    while (index < rhs.size) {
      if (rhs[index].controller != lhs[index].controller) {
        return false
      }
      index++
    }

    return true
  }

  fun setRouterOnController(controller: Controller) {
    //controller.setRouter(this)
    controller.onContextAvailable()
  }

  abstract fun invalidateOptionsMenu()
  abstract fun startActivity(intent: Intent)
  abstract fun startActivityForResult(instanceId: String, intent: Intent, requestCode: Int)
  abstract fun startActivityForResult(instanceId: String, intent: Intent, requestCode: Int, options: Bundle?)
  @Throws(IntentSender.SendIntentException::class)
  abstract fun startIntentSenderForResult(instanceId: String, intent: IntentSender, requestCode: Int, fillInIntent: Intent?, flagsMask: Int, flagsValues: Int, extraFlags: Int, options: Bundle?)
  abstract fun registerForActivityResult(instanceId: String, requestCode: Int)
  abstract fun unregisterForActivityResults(instanceId: String)
  abstract fun requestPermissions(instanceId: String, permissions: Array<String>, requestCode: Int)
  abstract fun hasHost(): Boolean
  abstract fun getSiblingRouters(): List<Router>
  abstract fun getRootRouter(): Router
  abstract fun getTransactionIndexer(): TransactionIndexer

  /**
   * Defines the way a Router will handle back button or pop events when there is only one controller
   * left in the backstack.
   */
  enum class PopRootControllerMode {
    /**
     * The Router will not pop the final controller left on the backstack when the back button is pressed
     * or when pop events are called. This mode is the default for Activity-hosted routers.
     */
    NEVER,
    /**
     * The Router will pop the final controller, but will leave its view in the hierarchy. This is useful
     * when the developer wishes to allow its containing Activity to finish or otherwise hide its parent
     * view without any strange artifacting.
     */
    POP_ROOT_CONTROLLER_BUT_NOT_VIEW,
    /**
     * The Router will pop both the final controller as well as its view.
     */
    POP_ROOT_CONTROLLER_AND_VIEW
  }

}
