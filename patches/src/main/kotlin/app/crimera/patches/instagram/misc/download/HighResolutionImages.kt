/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.download

import app.crimera.patches.instagram.utils.Constants.DOWNLOAD_DESCRIPTOR
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.string
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.matchSingle
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.registersUsed
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val IMAGE_DOWNLOAD = "$DOWNLOAD_DESCRIPTOR/HighResolutionImages;"
private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"

private data class ImageRequestAccess(val factory: Method, val auth: Method, val userAgent: Method)

context(context: BytecodePatchContext)
private fun imageRequestAccess(): ImageRequestAccess? {
    val ua = Fingerprint(
        returnType = "Ljava/lang/String;",
        parameters = listOf("Landroid/content/Context;"),
        filters = listOf(string("%sdpi; %sx%s"), string("%s %s Android %s")),
    ).matchAll().singleOrNull()?.originalMethod ?: return null
    if (!AccessFlags.PUBLIC.isSet(ua.accessFlags) || !AccessFlags.STATIC.isSet(ua.accessFlags)
        || !AccessFlags.PUBLIC.isSet(context.classDefBy(ua.definingClass).accessFlags)
    ) return null
    val headers = Fingerprint(
        definingClass = "Lcom/instagram/service/tigon/IGTigonAuthHandler;",
        name = "getAllHeaders",
        returnType = "Ljava/util/Map;",
        parameters = listOf("Ljava/lang/String;"),
        filters = listOf(string("Authorization")),
    ).matchAll().singleOrNull()?.originalMethod ?: return null
    val instructions = headers.implementation?.instructions?.toList() ?: return null
    val index = instructions.indices.singleOrNull {
        instructions[it].getReference<StringReference>()?.string == "Authorization"
    } ?: return null
    if (index < 2 || index + 1 >= instructions.size) return null
    val call = instructions[index - 2]
    val result = instructions[index - 1]
    val insertion = instructions[index + 1]
    val getter = call.getReference<MethodReference>() ?: return null
    if (call.opcode != Opcode.INVOKE_VIRTUAL || result.opcode != Opcode.MOVE_RESULT_OBJECT
        || getter.parameterTypes.isNotEmpty() || getter.returnType != "Ljava/lang/String;"
        || insertion.opcode != Opcode.INVOKE_STATIC
        || insertion.registersUsed.size != 3
        || result.registersUsed.singleOrNull() != insertion.registersUsed[2]
        || instructions[index].registersUsed.singleOrNull() != insertion.registersUsed[1]
    ) return null
    val store = context.classDefBy(getter.definingClass)
    if (!AccessFlags.PUBLIC.isSet(store.accessFlags)) return null
    val auth = store.methods.singleOrNull { it.toString() == getter.toString() } ?: return null
    if (!AccessFlags.PUBLIC.isSet(auth.accessFlags) || AccessFlags.STATIC.isSet(auth.accessFlags)) return null
    val ancestors = mutableSetOf<String>()
    var type: String? = USER_SESSION
    while (type != null && type != "Ljava/lang/Object;" && ancestors.add(type)) {
        type = context.classDefBy(type).superclass
    }
    val factory = store.methods.singleOrNull {
        AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
            && it.returnType == store.type && it.parameterTypes.size == 1
            && it.parameterTypes[0].toString() in ancestors
    } ?: return null
    return ImageRequestAccess(factory, auth, ua)
}

/** Optional native access: no display hook or required resolution fingerprint for Download media. */
context(context: BytecodePatchContext)
internal fun enableHighResolutionImageRequests() {
    val access = try {
        imageRequestAccess()
    } catch (_: PatchException) {
        null
    } ?: return

    fun bridge(name: String, registers: Int, body: String) {
        val original = Fingerprint(definingClass = IMAGE_DOWNLOAD, name = name).matchSingle().method
        val replacement = ImmutableMethod(
            original.definingClass, original.name, original.parameters, original.returnType,
            original.accessFlags, original.annotations, original.hiddenApiRestrictions,
            MutableMethodImplementation(registers),
        ).toMutable().apply { addInstructions(0, body.trimIndent()) }
        context.mutableClassDefBy(original.definingClass).methods.apply {
            remove(original)
            add(replacement)
        }
    }
    bridge("authorization", 2, """
        invoke-static {p0}, ${access.factory}
        move-result-object v0
        invoke-virtual {v0}, ${access.auth}
        move-result-object v0
        return-object v0
    """)
    bridge("userAgent", 2, """
        invoke-static {p0}, ${access.userAgent}
        move-result-object v0
        return-object v0
    """)
    bridge("isAvailable", 1, """
        const/4 v0, 0x1
        return v0
    """)
}
