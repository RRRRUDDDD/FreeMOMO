package com.rud.freemomo.hook

data class UpdateConstructorSignature(val className: String, val parameterTypes: List<String>)
data class UpdateFieldSignature(
    val className: String,
    val name: String,
    val type: String,
    val isStatic: Boolean = false
)

/** Raw DEX identities from 5.6.00/900. Never reuse these obfuscated names on another version. */
object UpdateHookTargets {
    const val ACTIVITY = "com.maimemo.android.momo.settings.appinfo.AppUpgradeActivity"
    const val ABOUT = "com.maimemo.android.momo.settings.appinfo.AboutMaiMemoActivity"
    const val CHECK_RESULT = "com.maimemo.android.momo.settings.appinfo.c"
    const val INFO = "com.maimemo.android.momo.model.upgrade.AppUpgradeDialogInfo"
    const val MODEL = "com.maimemo.android.momo.upgrade.d"
    const val PRECHECK = "com.maimemo.android.momo.upgrade.c"
    const val PROCEED = "com.maimemo.android.momo.upgrade.b"
    const val RETRY = "com.maimemo.android.momo.upgrade.a"
    const val HELPER = "com.maimemo.android.momo.upgrade.InAppUpgradeHelper"
    const val RETRY_RECEIVER = "com.maimemo.android.momo.upgrade.MemoDownloadService\$NotificationReceiver"
    const val PROMPT = "com.maimemo.android.momo.upgrade.MemoAppUpgradeModel\$PromptType"
    const val PROGRESS = "com.maimemo.android.momo.databinding.ViewAppUpgradeProgressBinding"
    const val NOTIFICATION = "com.maimemo.android.momo.notification.Notification"
    const val RESPONSE = "com.maimemo.android.momo.network.response.notification.NotificationSyncResponse"
    const val RESPONSE_DATA = "$RESPONSE\$Data"
    private const val CONTEXT = "android.content.Context"
    private const val STRING = "java.lang.String"
    private const val OBJECT = "java.lang.Object"
    private const val VOID = "void"

    data class Profile(
        val methods: Map<String, MethodSignature>,
        val constructors: Map<String, UpdateConstructorSignature>,
        val fields: Map<String, UpdateFieldSignature>
    ) {
        fun validate(
            method: (MethodSignature) -> Boolean,
            constructor: (UpdateConstructorSignature) -> Boolean,
            field: (UpdateFieldSignature) -> Boolean
        ): Boolean = methods.values.all(method) && constructors.values.all(constructor) &&
            fields.values.all(field)
    }

    fun forVersion(versionCode: Int): Profile? = if (versionCode == 900) verified900 else null

    private fun method(owner: String, name: String, vararg parameters: String,
                       result: String = VOID, static: Boolean = false) =
        MethodSignature(owner, name, parameters.toList(), result, static)

    private fun constructor(owner: String, vararg parameters: String) =
        UpdateConstructorSignature(owner, parameters.toList())

    private val verified900 = Profile(
        methods = linkedMapOf(
            "page.upgrade" to method(ACTIVITY, "U0", "android.view.ViewGroup", "android.os.Bundle"),
            "page.about" to method(ABOUT, "U0", "android.view.ViewGroup", "android.os.Bundle"),
            "page.destroy" to method("android.app.Activity", "onDestroy"),
            "request.get" to method("q60", "get", result = OBJECT),
            "result.upgrade" to method(CHECK_RESULT, "accept", OBJECT),
            "result.about" to method("u99", "accept", OBJECT),
            "click.upgrade" to method("z70", "onClick", "android.view.View"),
            "click.dialog" to method("jx6", "invoke", result = OBJECT),
            "dispatch" to method("wpd", "e", CONTEXT, INFO, static = true),
            "model.approve" to method(MODEL, "d"),
            "model.download" to method(MODEL, "e", STRING, PROMPT, "boolean", result = "boolean"),
            "model.install" to method(MODEL, "c", STRING),
            "model.close" to method(MODEL, "a", "boolean"),
            "callback.precheck" to method(PRECHECK, "accept", OBJECT),
            "callback.proceed" to method(PROCEED, "run"),
            "callback.cancel" to method("oq6", "run"),
            "callback.install" to method("gs2", "run"),
            "callback.retry" to method(RETRY, "a",
                "com.maimemo.android.momo.ui.widget.dialog.MMCommonDialogFragment", "hi6",
                result = "boolean"),
            "connection.connected" to method("q55", "onServiceConnected",
                "android.content.ComponentName", "android.os.IBinder"),
            "connection.disconnected" to method("q55", "onServiceDisconnected", "android.content.ComponentName"),
            "foreground.progress" to method("lx6", "a", "int"),
            "foreground.failure" to method("lx6", "b", "java.lang.Throwable"),
            "foreground.complete" to method("lx6", "c", STRING),
            "background.progress" to method("p55", "a", "int"),
            "background.failure" to method("p55", "b", "java.lang.Throwable"),
            "background.complete" to method("p55", "c", STRING),
            "helper.network" to method(HELPER, "checkNetworkStatusOnDownloadFile", CONTEXT, "long",
                "java.lang.Runnable", "java.lang.Runnable", static = true),
            "helper.start" to method(HELPER, "startDownload", CONTEXT, STRING, "boolean", "e70",
                result = "android.content.ServiceConnection", static = true),
            "helper.retry" to method(HELPER, "retryDownload", CONTEXT, "boolean", static = true),
            "helper.stop" to method(HELPER, "stopDownload", CONTEXT, static = true),
            "retry.pendingIntent" to method("android.app.PendingIntent", "getBroadcast", CONTEXT,
                "int", "android.content.Intent", "int", result = "android.app.PendingIntent", static = true),
            "retry.receive" to method(RETRY_RECEIVER, "onReceive", CONTEXT, "android.content.Intent"),
            "notifications.sync" to method("mk7", "k", RESPONSE, static = true),
            "notifications.consume" to method("vsd", "accept", OBJECT),
            "notifications.dialog" to method("com.maimemo.android.momo.notification.d", "a",
                "$NOTIFICATION\$Dialog", "android.app.Activity", NOTIFICATION, "boolean",
                "mc4", "p79", "int", static = true),
            "notifications.im" to method(NOTIFICATION, "isIM", result = "boolean")
        ),
        constructors = linkedMapOf(
            "request" to constructor("q60", "int"),
            "result.upgrade" to constructor(CHECK_RESULT, ACTIVITY),
            "result.about" to constructor("u99", "int", OBJECT),
            "model" to constructor(MODEL, CONTEXT, INFO),
            "precheck" to constructor(PRECHECK, MODEL, STRING, PROMPT),
            "proceed" to constructor(PROCEED, MODEL, STRING, PROMPT),
            "cancel" to constructor("oq6", "int", OBJECT),
            "dialog" to constructor("jx6", MODEL, "int"),
            "install" to constructor("gs2", OBJECT, "int", OBJECT),
            "retry" to constructor(RETRY, "kx6", MODEL),
            "connection" to constructor("q55", "boolean", "e70"),
            "foreground" to constructor("lx6", MODEL, PROGRESS),
            "background" to constructor("p55", "int", OBJECT)
        ),
        fields = linkedMapOf(
            "request.selector" to UpdateFieldSignature("q60", "a", "int"),
            "about.selector" to UpdateFieldSignature("u99", "a", "int"),
            "about.owner" to UpdateFieldSignature("u99", "b", OBJECT),
            "upgrade.selector" to UpdateFieldSignature("z70", "a", "int"),
            "dialog.selector" to UpdateFieldSignature("jx6", "a", "int"),
            "dialog.owner" to UpdateFieldSignature("jx6", "b", MODEL),
            "cancel.selector" to UpdateFieldSignature("oq6", "a", "int"),
            "cancel.owner" to UpdateFieldSignature("oq6", "b", OBJECT),
            "install.selector" to UpdateFieldSignature("gs2", "a", "int"),
            "install.owner" to UpdateFieldSignature("gs2", "b", OBJECT),
            "background.selector" to UpdateFieldSignature("p55", "a", "int"),
            "notifications.selector" to UpdateFieldSignature("vsd", "a", "int"),
            "notification.code" to UpdateFieldSignature(NOTIFICATION, "code", STRING),
            "notification.upgrade" to UpdateFieldSignature(NOTIFICATION, "APP_UPGRADE", STRING, true),
            "notification.dialog" to UpdateFieldSignature(NOTIFICATION, "APP_UPGRADE_DIALOG", STRING, true),
            "response.data" to UpdateFieldSignature(RESPONSE, "data", RESPONSE_DATA),
            "response.notifications" to UpdateFieldSignature(RESPONSE_DATA, "notifications", "java.util.Set"),
            "info.title" to UpdateFieldSignature(INFO, "title", STRING),
            "info.message" to UpdateFieldSignature(INFO, "message", STRING)
        )
    )
}
