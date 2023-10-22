package com.bluelinelabs.conductor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.SparseArray
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import com.bluelinelabs.conductor.internal.ClassUtils
import com.bluelinelabs.conductor.internal.RouterRequiringFunc
import com.bluelinelabs.conductor.internal.ViewAttachHandler
import com.bluelinelabs.conductor.internal.parcelableArrayListCompat
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.util.UUID

private const val KEY_CLASS_NAME = "Controller.className"
private const val KEY_VIEW_STATE = "Controller.viewState"
private const val KEY_CHILD_ROUTERS = "Controller.childRouters"
private const val KEY_SAVED_STATE = "Controller.savedState"
private const val KEY_INSTANCE_ID = "Controller.instanceId"
private const val KEY_TARGET_INSTANCE_ID = "Controller.target.instanceId"
private const val KEY_ARGS = "Controller.args"
private const val KEY_NEEDS_ATTACH = "Controller.needsAttach"
private const val KEY_REQUESTED_PERMISSIONS = "Controller.requestedPermissions"
private const val KEY_OVERRIDDEN_PUSH_HANDLER = "Controller.overriddenPushHandler"
private const val KEY_OVERRIDDEN_POP_HANDLER = "Controller.overriddenPopHandler"
private const val KEY_VIEW_STATE_HIERARCHY = "Controller.viewState.hierarchy"
private const val KEY_VIEW_STATE_BUNDLE = "Controller.viewState.bundle"
private const val KEY_RETAIN_VIEW_MODE = "Controller.retainViewMode"

/**
 * A Controller manages portions of the UI. It is similar to an Activity or Fragment in that it manages its
 * own lifecycle and controls interactions between the UI and whatever logic is required. It is, however,
 * a much lighter weight component than either Activities or Fragments. While it offers several lifecycle
 * methods, they are much simpler and more predictable than those of Activities and Fragments.
 *
 * @param args Any arguments that need to be retained.
 */
abstract class Controller2 protected constructor(
  private val args: Bundle = Bundle(this::class.java.classLoader)
) {

  private var instanceId: String = UUID.randomUUID().toString()

  internal var viewState: Bundle? = null
  private var savedInstanceState: Bundle? = null

  internal var isBeingDestroyed: Boolean = false
  private var destroyed: Boolean = false
  private var attached: Boolean = false
  private var hasOptionsMenu: Boolean = false
  private var optionsMenuHidden: Boolean = false
  private var viewIsAttached: Boolean = false
  private var viewWasDetached: Boolean = false

  internal var router: Router2? = null
  internal var view: View? = null

  private var parentController: Controller2? = null
  private var targetInstanceId: String? = null

  private var needsAttach: Boolean = false
  private var attachedToUnownedParent: Boolean = false
  private var awaitingParentAttach: Boolean = false
  private var hasSavedViewState: Boolean = false
  internal var isDetachFrozen: Boolean = false
  internal var onBackPressedDispatcherEnabled: Boolean = false

  private var overriddenPushHandler: ControllerChangeHandler? = null
  private var overriddenPopHandler: ControllerChangeHandler? = null

  private var retainViewMode = RetainViewMode.RELEASE_DETACH
  private var viewAttachHandler: ViewAttachHandler? = null

  private val childRouters = mutableListOf<ControllerHostedRouter>()
  private val lifecycleListeners = mutableListOf<LifecycleListener>()
  private val requestedPermissions = ArrayList<String>()
  private val onRouterSetListeners = mutableListOf<RouterRequiringFunc>()

  private var destroyedView: WeakReference<View>? = null

  private var isPerformingExitTransition: Boolean = false
  private var isContextAvailable: Boolean = false

  internal val onBackPressedCallback = object : OnBackPressedCallback(true) {
    override fun handleOnBackPressed() {
      // Root-level routers should have PopRootControllerMode.NEVER, and so should never return false here.
      // This is meant to handle higher-level pops only, where the predictive back gesture doesn't come into play.
      val router = router ?: return

      if (!router.getRootRouter().handleBackDispatch()) {
        // Disable to ensure we don't have an infinite call loop.
        isEnabled = false
        getOnBackPressedDispatcher()?.onBackPressed()

        if (!isBeingDestroyed) {
          isEnabled = true
        }
      }
    }
  }

//  val lifecycleOwner = ControllerLifecycleOwner(this)

  init {
    ensureRequiredConstructor()
//    OwnViewTreeLifecycleAndRegistry.Companion.own(this)
  }

  /**
   * Called when the controller is ready to display its view. A valid view must be returned. The standard body
   * for this method will be {@code return inflater.inflate(R.layout.my_layout, container, false);}, plus
   * any binding code.
   *
   * @param inflater       The LayoutInflater that should be used to inflate views
   * @param container      The parent view that this Controller's view will eventually be attached to.
   *                       This Controller's view should NOT be added in this method. It is simply passed in
   *                       so that valid LayoutParams can be used during inflation.
   * @param savedViewState A bundle for the view's state, which would have been created in {@link #onSaveViewState(View, Bundle)},
   *                       or {@code null} if no saved state exists.
   */
  protected abstract fun onCreateView(inflater: LayoutInflater, container: ViewGroup, savedViewState: Bundle?): View

  /**
   * Returns the {@link Router} object that can be used for pushing or popping other Controllers
   */
  fun getRouter(): Router2? {
    return router
  }

  /**
   * Returns any arguments that were set in this Controller's constructor
   */
  fun getArgs(): Bundle {
    return args
  }

  /**
   * Retrieves the child {@link Router} for the given container/tag combination. Note that multiple
   * routers should not exist in the same container unless a lot of care is taken to maintain order
   * between them. Avoid using the same container unless you have a great reason to do so (ex: ViewPagers).
   * The only time this method will return {@code null} is when the child router does not exist prior
   * to calling this method and the createIfNeeded parameter is set to false.
   *
   * @param container              The ViewGroup that hosts the child Router
   * @param tag                    The router's tag or {@code null} if none is needed
   * @param createIfNeeded         If true, a router will be created if one does not yet exist. Else {@code null} will be returned in this case.
   * @param boundToHostContainerId If true, a router will only ever rebind with a container with the same view id on state restoration. Note that this must be set to true if the tag is null.
   */
  fun getChildRouter(container: ViewGroup, tag: String? = null, createIfNeeded: Boolean = true, boundToHostContainerId: Boolean = true): Router2? {
    val containerId = container.id
    if (containerId == View.NO_ID) {
      error("You must set an id on your container.")
    }

    val childRouter = childRouters.find { it.matches(containerId, tag) }

    if (childRouter == null) {
      if (createIfNeeded) {
        val newChildRouter = ControllerHostedRouter(container.id, tag, boundToHostContainerId)
//        newChildRouter.setHostContainer(this, container)
        childRouters.add(newChildRouter)

        if (isPerformingExitTransition) {
          newChildRouter.setDetachFrozen(true)
        }

        //return newChildRouter
      }
    } else if (!childRouter.hasHost()) {
      //childRouter.setHostContainer(this, container)
      childRouter.rebindIfNeeded()
    }

    return null
    //return childRouter
  }

  /**
   * Removes a child {@link Router} from this Controller. When removed, all Controllers currently managed by
   * the {@link Router} will be destroyed.
   *
   * @param childRouter The router to be removed
   */
  fun removeChildRouter(childRouter: Router2) {
//    if (childRouter is ControllerHostedRouter && childRouters.remove(childRouter)) {
//      childRouter.destroy(true)
//    }
  }

  /**
   * Returns whether or not this Controller has been destroyed.
   */
  fun isDestroyed(): Boolean {
    return destroyed
  }

  /**
   * Returns whether or not this Controller is currently in the process of being destroyed.
   */
  fun isBeingDestroyed(): Boolean {
    return isBeingDestroyed
  }

  /**
   * Returns whether or not this Controller is currently attached to a host View.
   */
  fun isAttached(): Boolean {
    return attached
  }

  /**
   * Return this Controller's View or {@code null} if it has not yet been created or has been
   * destroyed.
   */
  fun getView(): View? {
    return view
  }

  /**
   * Returns the host Activity of this Controller's {@link Router} or {@code null} if this
   * Controller has not yet been attached to an Activity or if the Activity has been destroyed.
   */
  fun getActivity(): Activity? {
    return router?.activity()
  }

  /**
   * Returns the OnBackPressedDispatcher for this Controller's {@link Router} or {@code null} if:
   *   - This Router has not yet been attached to an Activity
   *   - The attached Activity does not extend ComponentActivity
   *   - The Activity has been destroyed
   */
  fun getOnBackPressedDispatcher(): OnBackPressedDispatcher? {
    return router?.getOnBackPressedDispatcher()
  }

  /**
   * Returns the Resources from the host Activity or {@code null} if this Controller has not
   * yet been attached to an Activity or if the Activity has been destroyed.
   */
  fun getResources(): Resources? {
    val activity = getActivity()
    return activity?.resources
  }

  /**
   * Returns the Application Context derived from the host Activity or {@code null} if this Controller
   * has not yet been attached to an Activity or if the Activity has been destroyed.
   */
  fun getApplicationContext(): Context? {
    return getActivity()?.applicationContext
  }

  /**
   * Returns this Controller's parent Controller if it is a child Controller or {@code null} if
   * it has no parent.
   */
  fun getParentController(): Controller2? {
    return parentController
  }

  /**
   * Returns this Controller's instance ID, which is generated when the instance is created and
   * retained across restarts.
   */
  fun getInstanceId(): String {
    return instanceId
  }

  /**
   * Returns the Controller with the given instance id or {@code null} if no such Controller
   * exists. May return the Controller itself or a matching descendant
   *
   * @param instanceId The instance ID being searched for
   */
  internal fun findController(instanceId: String): Controller2? {
    if (this.instanceId == instanceId) {
      return this
    }

    for (router in childRouters) {
      val matchingChild = router.getControllerWithInstanceId(instanceId)
      if (matchingChild != null) {
        //return matchingChild
      }
    }
    return null
  }

  /**
   * Returns all of this Controller's child Routers
   */
  fun getChildRouters(): List<Router> {
    return childRouters.toList()
  }

  /**
   * Optional target for this Controller. One reason this could be used is to send results back to the Controller
   * that started this one. Target Controllers are retained across instances. It is recommended
   * that Controllers enforce that their target Controller conform to a specific Interface.
   *
   * @param target The Controller that is the target of this one.
   */
  fun setTargetController(target: Controller2?) {
    if (targetInstanceId != null) {
      throw RuntimeException("Target controller already set. A controller's target may only be set once.")
    }

    targetInstanceId = target?.instanceId
  }

  /**
   * Returns the target Controller that was set with the {@link #setTargetController(Controller)}
   * method or {@code null} if this Controller has no target.
   *
   * @return This Controller's target
   */
  fun getTargetController(): Controller2? {
    if (targetInstanceId != null) {
      //return router.getRootRouter().getControllerWithInstanceId(targetInstanceId)
    }
    return null
  }

  /**
   * Called when this Controller's View is being destroyed. This should overridden to unbind the View
   * from any local variables.
   *
   * @param view The View to which this Controller should be bound.
   */
  protected fun onDestroyView(view: View) {}

  /**
   * Called when this Controller begins the process of being swapped in or out of the host view.
   *
   * @param changeHandler The {@link ControllerChangeHandler} that's managing the swap
   * @param changeType    The type of change that's occurring
   */
  protected fun onChangeStarted(changeHandler: ControllerChangeHandler, changeType: ControllerChangeType) {}

  /**
   * Called when this Controller completes the process of being swapped in or out of the host view.
   *
   * @param changeHandler The {@link ControllerChangeHandler} that's managing the swap
   * @param changeType    The type of change that occurred
   */
  protected fun onChangeEnded(changeHandler: ControllerChangeHandler, changeType: ControllerChangeType) {}

  /**
   * Called when this Controller has a Context available to it. This will happen very early on in the lifecycle
   * (before a view is created). If the host activity is re-created (ex: for orientation change), this will be
   * called again when the new context is available.
   */
  protected fun onContextAvailable(context: Context) {}

  /**
   * Called when this Controller's Context is no longer available. This can happen when the Controller is
   * destroyed or when the host Activity is destroyed.
   */
  protected fun onContextUnavailable() {}

  /**
   * Called when this Controller is attached to its host ViewGroup
   *
   * @param view The View for this Controller (passed for convenience)
   */
  protected fun onAttach(view: View) {}

  /**
   * Called when this Controller is detached from its host ViewGroup
   *
   * @param view The View for this Controller (passed for convenience)
   */
  protected fun onDetach(view: View) {}

  /**
   * Called when this Controller has been destroyed.
   */
  protected fun onDestroy() {}

  /**
   * Called when this Controller's host Activity is started
   */
  protected fun onActivityStarted(activity: Activity) {}

  /**
   * Called when this Controller's host Activity is resumed
   */
  protected fun onActivityResumed(activity: Activity) {}

  /**
   * Called when this Controller's host Activity is paused
   */
  protected fun onActivityPaused(activity: Activity) {}

  /**
   * Called when this Controller's host Activity is stopped
   */
  protected fun onActivityStopped(activity: Activity) {}

  /**
   * Called to save this Controller's View state. As Views can be detached and destroyed as part of the
   * Controller lifecycle (ex: when another Controller has been pushed on top of it), care should be taken
   * to save anything needed to reconstruct the View.
   *
   * @param view     This Controller's View, passed for convenience
   * @param outState The Bundle into which the View state should be saved
   */
  protected fun onSaveViewState(view: View, outState: Bundle) {}

  /**
   * Restores data that was saved in the {@link #onSaveViewState(View, Bundle)} method. This should be overridden
   * to restore the View's state to where it was before it was destroyed.
   *
   * @param view           This Controller's View, passed for convenience
   * @param savedViewState The bundle that has data to be restored
   */
  protected fun onRestoreViewState(view: View, savedViewState: Bundle) {}

  /**
   * Called to save this Controller's state in the event that its host Activity is destroyed.
   *
   * @param outState The Bundle into which data should be saved
   */
  protected fun onSaveInstanceState(outState: Bundle) {}

  /**
   * Restores data that was saved in the {@link #onSaveInstanceState(Bundle)} method. This should be overridden
   * to restore this Controller's state to where it was before it was destroyed.
   *
   * @param savedInstanceState The bundle that has data to be restored
   */
  protected fun onRestoreInstanceState(savedInstanceState: Bundle) {}

  /**
   * Calls startActivity(Intent) from this Controller's host Activity.
   */
  fun startActivity(intent: Intent) {
    executeWithRouter {
      router?.startActivity(intent)
    }
  }

  /**
   * Calls startActivityForResult(Intent, int) from this Controller's host Activity.
   */
  fun startActivityForResult(intent: Intent, requestCode: Int) {
    executeWithRouter {
      router?.startActivityForResult(instanceId, intent, requestCode)
    }
  }

  /**
   * Calls startActivityForResult(Intent, int, Bundle) from this Controller's host Activity.
   */
  fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
    executeWithRouter {
      router?.startActivityForResult(instanceId, intent, requestCode, options)
    }
  }

  /**
   * Calls startIntentSenderForResult(IntentSender, int, Intent, int, int, int, Bundle) from this Controller's host Activity.
   */
  @Throws(IntentSender.SendIntentException::class)
  fun startIntentSenderForResult(intent: IntentSender, requestCode: Int, fillInIntent: Intent?, flagsMask: Int, flagsValues: Int, extraFlags: Int, options: Bundle?) {
    router?.startIntentSenderForResult(instanceId, intent, requestCode, fillInIntent, flagsMask, flagsValues, extraFlags, options)
  }

  /**
   * Registers this Controller to handle onActivityResult responses. Calling this method is NOT
   * necessary when calling {@link #startActivityForResult(Intent, int)}
   *
   * @param requestCode The request code being registered for.
   */
  fun registerForActivityResult(requestCode: Int) {
    executeWithRouter {
      router?.registerForActivityResult(instanceId, requestCode)
    }
  }

  /**
   * Should be overridden if this Controller has called startActivityForResult and needs to handle
   * the result.
   *
   * @param requestCode The requestCode passed to startActivityForResult
   * @param resultCode  The resultCode that was returned to the host Activity's onActivityResult method
   * @param data        The data Intent that was returned to the host Activity's onActivityResult method
   */
  fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {}

  /**
   * Calls requestPermission(String[], int) from this Controller's host Activity. Results for this request,
   * including {@link #shouldShowRequestPermissionRationale(String)} and
   * {@link #onRequestPermissionsResult(int, String[], int[])} will be forwarded back to this Controller by the system.
   */
  fun requestPermissions(permissions: Array<String>, requestCode: Int) {
    requestedPermissions.addAll(permissions)

    executeWithRouter {
      router?.requestPermissions(instanceId, permissions, requestCode)
    }
  }

  /**
   * Gets whether you should show UI with rationale for requesting a permission.
   * {@see android.app.Activity#shouldShowRequestPermissionRationale(String)}
   *
   * @param permission A permission this Controller has requested
   */
  fun shouldShowRequestPermissionRationale(permission: String): Boolean {
    return Build.VERSION.SDK_INT >= 23 && getActivity()?.shouldShowRequestPermissionRationale(permission) ?: false
  }

  /**
   * Should be overridden if this Controller has requested runtime permissions and needs to handle the user's response.
   *
   * @param requestCode  The requestCode that was used to request the permissions
   * @param permissions  The array of permissions requested
   * @param grantResults The results for each permission requested
   */
  fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {}

  /**
   * Should be overridden if this Controller needs to handle the back button being pressed.
   *
   * @return True if this Controller has consumed the back button press, otherwise false
   */
  @Deprecated("This method has been deprecated and should be replaced with registering an OnBackPressedCallback.")
  fun handleBack(): Boolean {
    childRouters.flatMap { it.backstack as Iterable<RouterTransaction> }
      .sortedWith { t1, t2 -> t2.transactionIndex - t1.transactionIndex }
      .forEach { routerTransaction ->
        val childController = routerTransaction.controller

        if (childController.isAttached && childController.router.handleBack()) {
          return true
        }
      }

    return false
  }

  /**
   * Adds a listener for all of this Controller's lifecycle events
   *
   * @param lifecycleListener The listener
   */
  fun addLifecycleListener(lifecycleListener: LifecycleListener) {
    if (!lifecycleListeners.contains(lifecycleListener)) {
      lifecycleListeners.add(lifecycleListener)
    }
  }

  /**
   * Removes a previously added lifecycle listener
   *
   * @param lifecycleListener The listener to be removed
   */
  fun removeLifecycleListener(lifecycleListener: LifecycleListener) {
    lifecycleListeners.remove(lifecycleListener)
  }

  /**
   * Returns this Controller's {@link RetainViewMode}. Defaults to {@link RetainViewMode#RELEASE_DETACH}.
   */
  fun getRetainViewMode(): RetainViewMode {
    return retainViewMode
  }

  /**
   * Sets this Controller's {@link RetainViewMode}, which will influence when its view will be released.
   * This is useful when a Controller's view hierarchy is expensive to tear down and rebuild.
   */
  fun setRetainViewMode(retainViewMode: RetainViewMode) {
    this.retainViewMode = retainViewMode
    if (this.retainViewMode == RetainViewMode.RELEASE_DETACH && !attached) {
      removeViewReference(null)
    }
  }

  /**
   * Returns the {@link ControllerChangeHandler} that should be used for pushing this Controller, or null
   * if the handler from the {@link RouterTransaction} should be used instead.
   */
  fun getOverriddenPushHandler(): ControllerChangeHandler? {
    return overriddenPushHandler
  }

  /**
   * Overrides the {@link ControllerChangeHandler} that should be used for pushing this Controller. If this is a
   * non-null value, it will be used instead of the handler from  the {@link RouterTransaction}.
   */
  fun overridePushHandler(overriddenPushHandler: ControllerChangeHandler?) {
    this.overriddenPushHandler = overriddenPushHandler
  }

  /**
   * Returns the {@link ControllerChangeHandler} that should be used for popping this Controller, or null
   * if the handler from the {@link RouterTransaction} should be used instead.
   */
  fun getOverriddenPopHandler(): ControllerChangeHandler? {
    return overriddenPopHandler
  }

  /**
   * Overrides the {@link ControllerChangeHandler} that should be used for popping this Controller. If this is a
   * non-null value, it will be used instead of the handler from  the {@link RouterTransaction}.
   */
  fun overridePopHandler(overriddenPopHandler: ControllerChangeHandler?) {
    this.overriddenPopHandler = overriddenPopHandler
  }

  /**
   * Registers/unregisters for participation in populating the options menu by receiving options-related
   * callbacks, such as {@link #onCreateOptionsMenu(Menu, MenuInflater)}
   *
   * @param hasOptionsMenu If true, this controller's options menu callbacks will be called.
   */
  fun setHasOptionsMenu(hasOptionsMenu: Boolean) {
    val invalidate = attached && !optionsMenuHidden && this.hasOptionsMenu != hasOptionsMenu

    this.hasOptionsMenu = hasOptionsMenu

    if (invalidate) {
      router?.invalidateOptionsMenu()
    }
  }

  /**
   * Sets whether or not this controller's menu items should be visible. This is useful for hiding the
   * controller's options menu items when its UI is hidden, and not just when it is detached from the
   * window (the default).
   *
   * @param optionsMenuHidden Defaults to false. If true, this controller's menu items will not be shown.
   */
  fun setOptionsMenuHidden(optionsMenuHidden: Boolean) {
    val invalidate = attached && hasOptionsMenu && this.optionsMenuHidden != optionsMenuHidden

    this.optionsMenuHidden = optionsMenuHidden

    if (invalidate) {
      router?.invalidateOptionsMenu()
    }
  }

  /**
   * Adds option items to the host Activity's standard options menu. This will only be called if
   * {@link #setHasOptionsMenu(boolean)} has been called.
   *
   * @param menu     The menu into which your options should be placed.
   * @param inflater The inflater that can be used to inflate your menu items.
   */
  fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {}

  /**
   * Prepare the screen's options menu to be displayed. This is called directly before showing the
   * menu and can be used modify its contents.
   *
   * @param menu The menu that will be displayed
   */
  fun onPrepareOptionsMenu(menu: Menu) {}

  /**
   * Called when an option menu item has been selected by the user.
   *
   * @param item The selected item.
   * @return True if this event has been consumed, false if it has not.
   */
  fun onOptionsItemSelected(item: MenuItem): Boolean {
    return false
  }

  internal fun setNeedsAttach(needsAttach: Boolean) {
    this.needsAttach = needsAttach
  }

  internal fun prepareForHostDetach() {
    needsAttach = needsAttach || attached

    for (router in childRouters) {
      router.prepareForHostDetach()
    }
  }

  internal fun getNeedsAttach(): Boolean {
    return needsAttach
  }

  internal fun didRequestPermission(permission: String): Boolean {
    return requestedPermissions.contains(permission)
  }

  internal fun requestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
    requestedPermissions.removeAll(permissions)
    onRequestPermissionsResult(requestCode, permissions, grantResults)
  }

  internal fun setRouter(router: Router2) {
    if (this.router != router) {
      this.router = router

      performOnRestoreInstanceState()

      onRouterSetListeners.forEach { it.execute() }
      onRouterSetListeners.clear()
    } else {
      performOnRestoreInstanceState()
    }
  }

  internal fun onContextAvailable() {
    val context = router?.activity()

    if (context != null && !isContextAvailable) {
      lifecycleListeners.forEach { lifecycleListener ->
        lifecycleListener.preContextAvailable(this)
      }

      //onBackPressedDispatcherEnabled = router?.onBackPressedDispatcherEnable ?: false
      if (onBackPressedDispatcherEnabled) {
        if (context !is ComponentActivity) {
          error("Host activities must extend ComponentActivity when enabling OnBackPressedDispatcher support.")
        }
        getOnBackPressedDispatcher()?.addCallback(onBackPressedCallback)
      }

      isContextAvailable = true
      onContextAvailable(context)

      lifecycleListeners.forEach { listeners ->
        listeners.postContextAvailable(this, context)
      }
    }

    childRouters.forEach { childRouter ->
      childRouter.onContextAvailable()
    }
  }

  internal fun onContextUnavailable(context: Context) {
    childRouters.forEach { childRouter ->
      childRouter.onContextUnavailable(context)
    }

    if (isContextAvailable) {
      lifecycleListeners.forEach { lifecycleListener ->
        lifecycleListener.preContextUnavailable(this, context)
      }

      isContextAvailable = false
      onContextUnavailable()

      if (onBackPressedDispatcherEnabled) {
        onBackPressedCallback.remove()
      }

      lifecycleListeners.forEach { lifecycleListener ->
        lifecycleListener.postContextUnavailable(this)
      }
    }
  }

  internal fun executeWithRouter(listener: RouterRequiringFunc) {
    if (router != null) {
      listener.execute()
    } else {
      onRouterSetListeners.add(listener)
    }
  }

  internal fun activityStarted(activity: Activity) {
    if (viewAttachHandler != null) {
      viewAttachHandler?.onActivityStarted()
    }

    onActivityStarted(activity)
  }

  internal fun activityResumed(activity: Activity) {
    val view = view
    if (!attached && view != null && viewIsAttached) {
      attach(view)
    } else if (attached) {
      needsAttach = false
      hasSavedViewState = false
    }

    onActivityResumed(activity)
  }

  internal fun activityPaused(activity: Activity) {
    onActivityPaused(activity)
  }

  internal fun activityStopped(activity: Activity) {
    if (viewAttachHandler != null) {
      viewAttachHandler?.onActivityStopped()
    }

    if (attached && activity.isChangingConfigurations) {
      needsAttach = true
    }

    onActivityStopped(activity)
  }

  internal fun activityDestroyed(activity: Activity) {
    if (activity.isChangingConfigurations) {
      val currentView = view
      if (currentView != null) {
        detach(currentView, true, false)
      }
    } else {
      destroy(true)
    }

    onContextUnavailable(activity)
  }

  internal fun attach(view: View) {
    val router = router ?: return

    attachedToUnownedParent = view.parent != router.container
    if (attachedToUnownedParent || isBeingDestroyed) {
      return
    }

    if (parentController?.attached == false) {
      awaitingParentAttach = true
      return
    } else {
      awaitingParentAttach = false
    }

    hasSavedViewState = false


    lifecycleListeners.forEach { lifecycleListener ->
      lifecycleListener.preAttach(this, view)
    }

    attached = true
    needsAttach = router.isActivityStopped

    onAttach(view)

    if (hasOptionsMenu && !optionsMenuHidden) {
      router.invalidateOptionsMenu()
    }

    lifecycleListeners.forEach { lifecycleListener ->
      lifecycleListener.postAttach(this, view)
    }

    childRouters.forEach { childRouter ->
      childRouter.backstack.forEach { childTransaction ->
//        if (childTransaction.controller.awaitingParentAttach) {
//          childTransaction.controller.attach(childTransaction.controller.view)
//        }
      }

      if (childRouter.hasHost()) {
        childRouter.rebindIfNeeded()
      }
    }
  }

  internal fun detach(view: View, forceViewRefRemoval: Boolean, blockViewRefRemoval: Boolean) {
    if (!attachedToUnownedParent) {
      childRouters.forEach { childRouter ->
        childRouter.prepareForHostDetach()
      }
    }

    val removeViewRef = !blockViewRefRemoval && (forceViewRefRemoval || retainViewMode == RetainViewMode.RELEASE_DETACH || isBeingDestroyed)

    if (attached) {
      if (!awaitingParentAttach) {

        lifecycleListeners.forEach { lifecycleListener ->
          lifecycleListener.preDetach(this, view)
        }

        attached = false
        onDetach(view)

        if (hasOptionsMenu && !optionsMenuHidden) {
          router?.invalidateOptionsMenu()
        }

        lifecycleListeners.forEach { lifecycleListener ->
          lifecycleListener.postDetach(this, view)
        }
      } else {
        attached = false
      }
    }

    awaitingParentAttach = false

    if (removeViewRef) {
      removeViewReference(view.context)
    }
  }

  private fun removeViewReference(context: Context?) {
    val view = view

    if (view != null) {
      if (!isBeingDestroyed && !hasSavedViewState) {
        saveViewState(view)
      }

      lifecycleListeners.forEach { lifecycleListener ->
        lifecycleListener.preDestroyView(this, view)
      }

      onDestroyView(view)

      // viewAttachHandler may be null iff the controller was popped before we got here
      if (viewAttachHandler != null) {
        viewAttachHandler?.unregisterAttachListener(view)
      }

      viewAttachHandler = null
      viewIsAttached = false

      if (isBeingDestroyed) {
        destroyedView = WeakReference(view)
      }
      this.view = null

      lifecycleListeners.forEach { lifecycleListener ->
        lifecycleListener.postDestroyView(this)
      }

      childRouters.forEach { childRouter ->
        childRouter.removeHost()
      }
    }

    if (isBeingDestroyed) {
      performDestroy(context ?: view?.context)
    }
  }

  internal fun inflate(parent: ViewGroup): View {
    val currentView = view
    if (currentView != null && currentView.parent != parent) {
      detach(currentView, forceViewRefRemoval = true, blockViewRefRemoval = false)
      removeViewReference(currentView.context)
    }

    if (currentView == null) {

      lifecycleListeners.forEach { lifecycleListener ->
        lifecycleListener.preCreateView(this)
      }

      val savedViewState = viewState?.getBundle(KEY_VIEW_STATE_BUNDLE)
      val newView = onCreateView(LayoutInflater.from(parent.context), parent, savedViewState)

      this.view = newView

      if (newView == parent) {
        error("Controller's onCreateView method returned the parent ViewGroup. Perhaps you forgot to pass false for LayoutInflater.inflate's attachToRoot parameter?")
      }

      lifecycleListeners.forEach { lifecycleListener ->
        lifecycleListener.postCreateView(this, newView)
      }

      restoreViewState(newView)

      if (!isBeingDestroyed) {
        val viewAttachHandler = ViewAttachHandler(object: ViewAttachHandler.ViewAttachListener {

          override fun onAttached() {
            viewIsAttached = true
            viewWasDetached = false
            attach(newView)
          }

          override fun onDetached(fromActivityStop: Boolean) {
            viewIsAttached = false
            viewWasDetached = true

            if (!isDetachFrozen) {
              detach(newView, false, fromActivityStop)
            }
          }

          override fun onViewDetachAfterStop() {
            if (!isDetachFrozen) {
              detach(newView, false, false)
            }
          }

        })
        viewAttachHandler.listenForAttach(view)
        this.viewAttachHandler = viewAttachHandler
      }
    } else {
      restoreChildControllerHosts()
    }

    return view ?: error("happened error during inflating view")
  }

  private fun restoreChildControllerHosts() {
    childRouters.forEach { childRouter ->
      if (!childRouter.hasHost()) {
        val containerView = view?.findViewById<View>(childRouter.hostId())

        if (containerView is ViewGroup) {
          //childRouter.setHostContainer(this, containerView)
          childRouter.rebindIfNeeded()
        }
      }
    }
  }

  private fun performDestroy(context: Context?) {
    val ctx = context ?: getActivity() ?: return

    if (isContextAvailable) {
      onContextUnavailable(ctx)
    }

    if (!destroyed) {
      lifecycleListeners.forEach { lifecycleListener ->
        lifecycleListener.preDestroy(this)
      }

      destroyed = true

      onDestroy()

      parentController = null

      lifecycleListeners.forEach { lifecycleListener ->
        lifecycleListener.postDestroy(this)
      }
    }
  }

  internal fun destroy() {
    destroy(false)
  }

  private fun destroy(removeViews: Boolean) {
    isBeingDestroyed = true

    router?.unregisterForActivityResults(instanceId)

    childRouters.forEach { childRouter ->
      childRouter.destroy(false)
    }

    if (!attached) {
      removeViewReference(null)
    } else if (removeViews) {
      val currentView = view
      if (currentView != null) {
        detach(currentView, forceViewRefRemoval = true, blockViewRefRemoval = false)
      }
    }
  }

  private fun saveViewState(view: View) {
    hasSavedViewState = true

    val viewState = Bundle(javaClass.classLoader)
    this.viewState = viewState

    val hierarchyState = SparseArray<Parcelable>()
    view.saveHierarchyState(hierarchyState)
    viewState.putSparseParcelableArray(KEY_VIEW_STATE_HIERARCHY, hierarchyState)

    val stateBundle = Bundle(javaClass.classLoader)
    onSaveViewState(view, stateBundle)
    viewState.putBundle(KEY_VIEW_STATE_BUNDLE, stateBundle)

    lifecycleListeners.forEach { lifecycleListener ->
      lifecycleListener.onSaveViewState(this, viewState)
    }
  }

  private fun restoreViewState(view: View) {
    val viewState = viewState ?: return

    view.restoreHierarchyState(viewState.getSparseParcelableArray(KEY_VIEW_STATE_HIERARCHY))
    val savedViewState = viewState.getBundle(KEY_VIEW_STATE_BUNDLE) ?: Bundle()
    savedViewState.classLoader = javaClass.classLoader
    onRestoreViewState(view, savedViewState)

    restoreChildControllerHosts()

    lifecycleListeners.forEach { lifecycleListener ->
      //lifecycleListener.onRestoreViewState(this, viewState)
    }
  }

  internal fun saveInstanceState(): Bundle {
    val view = view
    if (!hasSavedViewState && view != null) {
      saveViewState(view)
    }

    val outState = Bundle()
    outState.putString(KEY_CLASS_NAME, javaClass.name)
    outState.putBundle(KEY_VIEW_STATE, viewState)
    outState.putBundle(KEY_ARGS, args)
    outState.putString(KEY_INSTANCE_ID, instanceId)
    outState.putString(KEY_TARGET_INSTANCE_ID, targetInstanceId)
    outState.putStringArrayList(KEY_REQUESTED_PERMISSIONS, requestedPermissions)
    outState.putBoolean(KEY_NEEDS_ATTACH, needsAttach || attached)
    outState.putInt(KEY_RETAIN_VIEW_MODE, retainViewMode.ordinal)

    if (overriddenPushHandler != null) {
      outState.putBundle(KEY_OVERRIDDEN_PUSH_HANDLER, overriddenPushHandler?.toBundle())
    }
    if (overriddenPopHandler != null) {
      outState.putBundle(KEY_OVERRIDDEN_POP_HANDLER, overriddenPopHandler?.toBundle())
    }

    val childBundles = ArrayList<Bundle>(childRouters.size)

    childRouters.forEach { childRouter ->
      val routerBundle = Bundle()
      childRouter.saveInstanceState(routerBundle)
      childBundles.add(routerBundle)
    }

    outState.putParcelableArrayList(KEY_CHILD_ROUTERS, childBundles)

    val savedState = Bundle(javaClass.classLoader)
    onSaveInstanceState(savedState)

    lifecycleListeners.forEach { lifecycleListener ->
      lifecycleListener.onSaveInstanceState(this, savedState)
    }

    outState.putBundle(KEY_SAVED_STATE, savedState)

    return outState
  }

  private fun restoreInstanceState(savedInstanceState: Bundle) {
    viewState = savedInstanceState.getBundle(KEY_VIEW_STATE)
    viewState?.classLoader = javaClass.classLoader

    instanceId = savedInstanceState.getString(KEY_INSTANCE_ID).orEmpty()
    targetInstanceId = savedInstanceState.getString(KEY_TARGET_INSTANCE_ID)
    requestedPermissions.addAll(savedInstanceState.getStringArrayList(KEY_REQUESTED_PERMISSIONS).orEmpty())
    overriddenPushHandler = ControllerChangeHandler.fromBundle(savedInstanceState.getBundle(KEY_OVERRIDDEN_PUSH_HANDLER))
    overriddenPopHandler = ControllerChangeHandler.fromBundle(savedInstanceState.getBundle(KEY_OVERRIDDEN_POP_HANDLER))
    needsAttach = savedInstanceState.getBoolean(KEY_NEEDS_ATTACH)
    retainViewMode = RetainViewMode.values()[savedInstanceState.getInt(KEY_RETAIN_VIEW_MODE, 0)]

    savedInstanceState.parcelableArrayListCompat<Bundle>(KEY_CHILD_ROUTERS)?.forEach { childBundle ->
      val childRouter = ControllerHostedRouter()
      //childRouter.setHostController(this)
      childRouter.restoreInstanceState(childBundle)
      childRouters.add(childRouter)
    }

    this.savedInstanceState = savedInstanceState.getBundle(KEY_SAVED_STATE)
    this.savedInstanceState?.classLoader = javaClass.classLoader

    performOnRestoreInstanceState()
  }

  private fun performOnRestoreInstanceState() {
    val savedInstanceState = savedInstanceState ?: return

    onRestoreInstanceState(savedInstanceState)

    lifecycleListeners.forEach { lifecycleListener ->
      lifecycleListener.onRestoreInstanceState(this, savedInstanceState)
    }

    this.savedInstanceState = null
  }

  internal fun changeStarted(changeHandler: ControllerChangeHandler, changeType: ControllerChangeType) {
    if (!changeType.isEnter) {
      isPerformingExitTransition = true

      childRouters.forEach { childRouter ->
        childRouter.setDetachFrozen(true)
      }
    }

    onChangeStarted(changeHandler, changeType)

    lifecycleListeners.forEach { lifecycleListener ->
      lifecycleListener.onChangeStart(this, changeHandler, changeType)
    }
  }

  internal fun changeEnded(changeHandler: ControllerChangeHandler, changeType: ControllerChangeType) {
    if (!changeType.isEnter) {
      isPerformingExitTransition = false
      childRouters.forEach { router ->
        router.setDetachFrozen(false)
      }
    }

    onChangeEnded(changeHandler, changeType)

    lifecycleListeners.forEach { lifecycleListener ->
      lifecycleListener.onChangeEnd(this, changeHandler, changeType)
    }

    val destroyedView = destroyedView

    if (isBeingDestroyed && !viewIsAttached && !attached && destroyedView != null) {
      val view = destroyedView.get()

      val container = router?.container
      if (container != null && view != null && view.parent == container) {
        container.removeView(view)
      }

      this.destroyedView = null
    }

    changeHandler.onEnd()
  }

  internal fun setDetachFrozen(frozen: Boolean) {
    if (isDetachFrozen != frozen) {
      isDetachFrozen = frozen;

      val detach = !frozen && view != null && viewWasDetached

      childRouters.forEach { childRouter ->
        if (detach) {
          childRouter.prepareForHostDetach()
        }

        childRouter.setDetachFrozen(frozen)
      }

      if (detach) {
        val view = view

        if (view != null) {
          detach(view, forceViewRefRemoval = false, blockViewRefRemoval = false)
        }
//        if (view == null && savedView?.parent == router?.container) {
//          router?.container?.removeView(savedView) // need to remove the view when this controller is a child controller
//        }
      }
    }
  }

  internal fun createOptionsMenu(menu: Menu, inflater: MenuInflater) {
    if (attached && hasOptionsMenu && !optionsMenuHidden) {
      onCreateOptionsMenu(menu, inflater)
    }
  }

  internal fun prepareOptionsMenu(menu: Menu) {
    if (attached && hasOptionsMenu && !optionsMenuHidden) {
      onPrepareOptionsMenu(menu)
    }
  }

  internal fun optionsItemSelected(item: MenuItem): Boolean {
    return attached && hasOptionsMenu && !optionsMenuHidden && onOptionsItemSelected(item)
  }

  internal fun setParentController(controller: Controller2?) {
    parentController = controller
  }

  private fun ensureRequiredConstructor() {
    val constructors = javaClass.constructors
    if (getBundleConstructor(constructors) == null && getDefaultConstructor(constructors) == null) {
      throw RuntimeException("$javaClass does not have a constructor that takes a Bundle argument or a default constructor. Controllers must have one of these in order to restore their states.");
    }
  }

  /**
   * Modes that will influence when the Controller will allow its view to be destroyed
   */
  enum class RetainViewMode {
    /**
     * The Controller will release its reference to its view as soon as it is detached.
     */
    RELEASE_DETACH,
    /**
     * The Controller will retain its reference to its view when detached, but will still release the reference when a config change occurs.
     */
    RETAIN_DETACH
  }

  /**
   * Allows external classes to listen for lifecycle events in a Controller
   */
  class LifecycleListener {

    fun onChangeStart(controller: Controller2, changeHandler: ControllerChangeHandler, changeType: ControllerChangeType) {}

    fun onChangeEnd(controller: Controller2, changeHandler: ControllerChangeHandler, changeType: ControllerChangeType) {}

    fun preCreateView(controller: Controller2) {}

    fun postCreateView(controller: Controller2, view: View) {}

    fun preAttach(controller: Controller2, view: View) {}

    fun postAttach(controller: Controller2, view: View) {}

    fun preDetach(controller: Controller2, view: View) {}

    fun postDetach(controller: Controller2, view: View) {}

    fun preDestroyView(controller: Controller2, view: View) {}

    fun postDestroyView(controller: Controller2) {}

    fun preDestroy(controller: Controller2) {}

    fun postDestroy(controller: Controller2) {}

    fun preContextAvailable(controller: Controller2) {}

    fun postContextAvailable(controller: Controller2, context: Context) {}

    fun preContextUnavailable(controller: Controller2, context: Context) {}

    fun postContextUnavailable(controller: Controller2) {}

    fun onSaveInstanceState(controller: Controller2, outState: Bundle) {}

    fun onRestoreInstanceState(controller: Controller2, savedInstanceState: Bundle) {}

    fun onSaveViewState(controller: Controller2, outState: Bundle) {}

    fun onRestoreViewState(controller: Controller, savedViewState: Bundle) {}

  }

  companion object {
    internal fun newInstance(bundle: Bundle): Controller2 {
      val className = bundle.getString(KEY_CLASS_NAME).orEmpty()

      val cls = ClassUtils.classForName<Controller2>(className, false) ?: error("")

      val constructors = cls.constructors
      val bundleConstructor = getBundleConstructor(constructors)

      val args = bundle.getBundle(KEY_ARGS)
      args?.classLoader = cls.classLoader

      //Controller controller;
      val controller = try {
        if (bundleConstructor != null) {
          bundleConstructor.newInstance(args) as Controller2
        } else {
          //noinspection ConstantConditions
          val controllerFromDefaultConstructor = getDefaultConstructor(constructors)?.newInstance() as Controller2

          // Restore the args that existed before the last process death
          if (args != null) {
            controllerFromDefaultConstructor.args.putAll(args);
          }

          controllerFromDefaultConstructor
        }
      } catch (e: Exception) {
        throw RuntimeException("An exception occurred while creating a new instance of " + className + ". " + e.message, e)
      }

      controller.restoreInstanceState(bundle)
      return controller
    }

    private fun getDefaultConstructor(constructors: Array<Constructor<*>>): Constructor<*>? {
      for (constructor in constructors) {
        if (constructor.parameterTypes.isEmpty()) {
          return constructor
        }
      }
      return null
    }

    private fun getBundleConstructor(constructors: Array<Constructor<*>>): Constructor<*>? {
      for (constructor in constructors) {
        if (constructor.parameterTypes.size == 1 && constructor.parameterTypes[0] == Bundle::class.java) {
            return constructor
          }
      }
      return null
    }

  }

}




















