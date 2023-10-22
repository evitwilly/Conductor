package com.bluelinelabs.conductor

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.content.IntentSender.SendIntentException
import android.os.Bundle
import android.view.ViewGroup
import com.bluelinelabs.conductor.ControllerChangeHandler.ControllerChangeListener
import com.bluelinelabs.conductor.internal.LifecycleHandler
import com.bluelinelabs.conductor.internal.TransactionIndexer

class ActivityHostedRouter : Router() {
    private var lifecycleHandler: LifecycleHandler? = null
    private val transactionIndexer = TransactionIndexer()

    init {
        popRootControllerMode = PopRootControllerMode.NEVER
    }

    fun setHost(lifecycleHandler: LifecycleHandler, newContainer: ViewGroup) {
        val currentContainer = this.container
          if (this.lifecycleHandler != lifecycleHandler || currentContainer != newContainer) {
              if (currentContainer != null && currentContainer is ControllerChangeListener) {
                  removeChangeListener(currentContainer)
              }
              if (newContainer is ControllerChangeListener) {
                  addChangeListener(newContainer)
              }
              this.lifecycleHandler = lifecycleHandler
              this.container = newContainer
              watchContainerAttach()
          }
    }

    override fun saveInstanceState(outState: Bundle) {
        super.saveInstanceState(outState)
        transactionIndexer.saveInstanceState(outState)
    }

    override fun restoreInstanceState(savedInstanceState: Bundle) {
        super.restoreInstanceState(savedInstanceState)
        transactionIndexer.restoreInstanceState(savedInstanceState)
    }

    override fun getActivity(): Activity? {
        return lifecycleHandler?.lifecycleActivity
    }

    override fun onActivityDestroyed(activity: Activity, isConfigurationChange: Boolean) {
        super.onActivityDestroyed(activity, isConfigurationChange)
        if (!isConfigurationChange) {
            lifecycleHandler = null
        }
    }

    override fun invalidateOptionsMenu() {
        if (lifecycleHandler != null) {
            activity?.invalidateOptionsMenu()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        lifecycleHandler?.onActivityResult(requestCode, resultCode, data) ?: throwMissingLifecycleHandlerError()
    }

    override fun startActivity(intent: Intent) {
        lifecycleHandler?.startActivity(intent) ?: throwMissingLifecycleHandlerError()
    }

    override fun startActivityForResult(instanceId: String, intent: Intent, requestCode: Int) {
        lifecycleHandler?.startActivityForResult(instanceId, intent, requestCode, null) ?: throwMissingLifecycleHandlerError()
    }

    override fun startActivityForResult(instanceId: String, intent: Intent, requestCode: Int, options: Bundle?) {
        lifecycleHandler?.startActivityForResult(instanceId, intent, requestCode, options) ?: throwMissingLifecycleHandlerError()
    }

    @Throws(SendIntentException::class)
    override fun startIntentSenderForResult(
        instanceId: String, intent: IntentSender, requestCode: Int, fillInIntent: Intent?,
        flagsMask: Int, flagsValues: Int, extraFlags: Int, options: Bundle?
    ) {
        lifecycleHandler?.startIntentSenderForResult(instanceId, intent, requestCode, fillInIntent, flagsMask,
            flagsValues, extraFlags, options) ?: throwMissingLifecycleHandlerError()
    }

    override fun registerForActivityResult(instanceId: String, requestCode: Int) {
        lifecycleHandler?.registerForActivityResult(instanceId, requestCode) ?: throwMissingLifecycleHandlerError()
    }

    override fun unregisterForActivityResults(instanceId: String) {
        lifecycleHandler?.unregisterForActivityResults(instanceId) ?: throwMissingLifecycleHandlerError()
    }

    override fun requestPermissions(instanceId: String, permissions: Array<String>, requestCode: Int) {
        lifecycleHandler?.requestPermissions(instanceId, permissions, requestCode) ?: throwMissingLifecycleHandlerError()
    }

    override fun hasHost(): Boolean {
        return lifecycleHandler != null
    }

    override fun getSiblingRouters(): List<Router> {
        return lifecycleHandler?.routers.orEmpty()
    }

    override fun getRootRouter(): Router {
        return this
    }

    override fun getTransactionIndexer(): TransactionIndexer {
        return transactionIndexer
    }

    public override fun onContextAvailable() {
        super.onContextAvailable()
    }

    private fun throwMissingLifecycleHandlerError(): Nothing {
        throw IllegalStateException("LifecycleHandler is missing")
    }
}
