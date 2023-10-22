package com.bluelinelabs.conductor

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.content.IntentSender.SendIntentException
import android.os.Bundle
import android.text.TextUtils
import android.view.ViewGroup
import androidx.annotation.IdRes
import com.bluelinelabs.conductor.ControllerChangeHandler.ControllerChangeListener
import com.bluelinelabs.conductor.internal.TransactionIndexer
import java.util.Locale

private const val KEY_HOST_ID = "ControllerHostedRouter.hostId"
private const val KEY_TAG = "ControllerHostedRouter.tag"
private const val KEY_BOUND_TO_CONTAINER = "ControllerHostedRouter.boundToContainer"

internal class ControllerHostedRouter(
    @IdRes private var hostId: Int = 0,
    private var tag: String? = null,
    private var boundToContainer: Boolean = false
) : Router() {

  private var hostController: Controller? = null

    private var isDetachFrozen = false

    init {
        check(!(!boundToContainer && tag == null)) { "ControllerHostedRouter can't be created without a tag if not bounded to its container" }

        popRootControllerMode = PopRootControllerMode.POP_ROOT_CONTROLLER_BUT_NOT_VIEW
    }

    fun setHostController(controller: Controller) {
        if (hostController == null) {
            hostController = controller
            setOnBackPressedDispatcherEnabled(controller.onBackPressedDispatcherEnabled)
        }
    }

    fun setHostContainer(controller: Controller, container: ViewGroup) {
        if (hostController !== controller || this.container !== container) {
            removeHost()
            if (container is ControllerChangeListener) {
                addChangeListener(container)
            }
            hostController = controller
            this.container = container
            setOnBackPressedDispatcherEnabled(controller.onBackPressedDispatcherEnabled)
            for (transaction in backstack) {
                transaction.controller.parentController = controller
            }
            watchContainerAttach()
        }
    }

    fun removeHost() {
        val currentContainer = container
        if (currentContainer != null && currentContainer is ControllerChangeListener) {
            removeChangeListener(currentContainer)
        }
        for (controller in destroyingControllers) {
            if (controller.getView() != null) {
                controller.detach(controller.getView(), true, false)
            }
        }
        for (transaction in backstack) {
            if (transaction.controller.getView() != null) {
                transaction.controller.detach(transaction.controller.getView(), true, false)
            }
        }
        prepareForContainerRemoval()
        container = null
    }

    fun setDetachFrozen(frozen: Boolean) {
        isDetachFrozen = frozen
        for (transaction in backstack) {
            transaction.controller.setDetachFrozen(frozen)
        }
    }

    fun hostId() = hostId

    public override fun destroy(popViews: Boolean) {
        setDetachFrozen(false)
        super.destroy(popViews)
    }

    override fun pushToBackstack(entry: RouterTransaction) {
        if (isDetachFrozen) {
            entry.controller.setDetachFrozen(true)
        }
        super.pushToBackstack(entry)
    }

    override fun setBackstack(newBackstack: List<RouterTransaction>, changeHandler: ControllerChangeHandler?) {
        if (isDetachFrozen) {
            for (transaction in newBackstack) {
                transaction.controller.setDetachFrozen(true)
            }
        }
        super.setBackstack(newBackstack, changeHandler)
    }

    override fun performControllerChange(to: RouterTransaction?, from: RouterTransaction?, isPush: Boolean) {
        super.performControllerChange(to, from, isPush)

        val hostController = hostController ?: return
        // If we're pushing a transaction that will detach controllers to an unattached child
        // router, we need mark all other controllers as NOT needing to be reattached.
        if (to != null && !hostController.isAttached) {
            val pushChangeHandler = to.pushChangeHandler()
            if (pushChangeHandler == null || pushChangeHandler.removesFromViewOnPush) {
                for (transaction in backstack) {
                    transaction.controller.needsAttach = false
                }
            }
        }
    }

    override fun getActivity(): Activity? {
        return hostController?.activity
    }

    override fun onActivityDestroyed(activity: Activity, isConfigurationChange: Boolean) {
        super.onActivityDestroyed(activity, isConfigurationChange)
        removeHost()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (hostController != null && hostController!!.getRouter() != null) {
            hostController!!.getRouter().onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun invalidateOptionsMenu() {
        if (hostController != null && hostController!!.getRouter() != null) {
            hostController!!.getRouter().invalidateOptionsMenu()
        }
    }

    override fun startActivity(intent: Intent) {
        val router = hostController?.getRouter() ?: return
        router.startActivity(intent)
    }

    override fun startActivityForResult(instanceId: String, intent: Intent, requestCode: Int) {
        val router = hostController?.getRouter() ?: return
        router.startActivityForResult(instanceId, intent, requestCode)
    }

    override fun startActivityForResult(instanceId: String, intent: Intent, requestCode: Int, options: Bundle?) {
        val router = hostController?.getRouter() ?: return
        router.startActivityForResult(instanceId, intent, requestCode, options)
    }

    @Throws(SendIntentException::class)
    override fun startIntentSenderForResult(instanceId: String, intent: IntentSender, requestCode: Int,
        fillInIntent: Intent?, flagsMask: Int, flagsValues: Int, extraFlags: Int, options: Bundle?) {
        val router = hostController?.getRouter() ?: return
        router.startIntentSenderForResult(instanceId, intent, requestCode, fillInIntent, flagsMask,
            flagsValues, extraFlags, options)
    }

    override fun registerForActivityResult(instanceId: String, requestCode: Int) {
        val router =  hostController?.getRouter() ?: return
        router.registerForActivityResult(instanceId, requestCode)
    }

    override fun unregisterForActivityResults(instanceId: String) {
        if (hostController != null && hostController!!.getRouter() != null) {
            hostController!!.getRouter().unregisterForActivityResults(instanceId)
        }
    }

    override fun requestPermissions(instanceId: String, permissions: Array<String>, requestCode: Int) {
        val router = hostController?.getRouter() ?: return
        router.requestPermissions(instanceId, permissions, requestCode)
    }

    public override fun hasHost(): Boolean {
        return hostController != null && container != null
    }

    override fun saveInstanceState(outState: Bundle) {
        super.saveInstanceState(outState)
        outState.putInt(KEY_HOST_ID, hostId)
        outState.putBoolean(KEY_BOUND_TO_CONTAINER, boundToContainer)
        outState.putString(KEY_TAG, tag)
    }

    override fun restoreInstanceState(savedInstanceState: Bundle) {
        super.restoreInstanceState(savedInstanceState)
        hostId = savedInstanceState.getInt(KEY_HOST_ID)
        boundToContainer = savedInstanceState.getBoolean(KEY_BOUND_TO_CONTAINER)
        tag = savedInstanceState.getString(KEY_TAG)
    }

    public override fun setRouterOnController(controller: Controller) {
        controller.parentController = hostController
        super.setRouterOnController(controller)
    }

    fun matches(hostId: Int, tag: String?): Boolean {
        if (!boundToContainer && container == null) {
            checkNotNull(this.tag) { "Host ID can't be variable with a null tag" }
            if (this.tag == tag) {
                this.hostId = hostId
                return true
            }
        }
        return this.hostId == hostId && TextUtils.equals(tag, this.tag)
    }

    override fun getSiblingRouters(): List<Router> {
        return hostController?.childRouters.orEmpty() + hostController?.router?.siblingRouters.orEmpty()
    }

    override fun getRootRouter(): Router {
        return hostController?.getRouter()?.rootRouter ?: this
    }

    override fun getTransactionIndexer(): TransactionIndexer {
        if (rootRouter == this) {
            val hostController = hostController
            val debugInfo = if (hostController != null) {
                String.format(
                    Locale.ENGLISH,
                    "%s (attached? %b, destroyed? %b, parent: %s)",
                    hostController.javaClass.simpleName,
                    hostController.isAttached,
                    hostController.isBeingDestroyed,
                    hostController.parentController
                )
            } else {
                "null host controller"
            }
            throw IllegalStateException("Unable to retrieve TransactionIndexer from $debugInfo")
        }
        return rootRouter.transactionIndexer
    }

}
