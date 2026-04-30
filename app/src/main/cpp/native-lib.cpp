#include <jni.h>
#include <string>
#include <vector>
#include <capstone/capstone.h>

extern "C" JNIEXPORT jstring JNICALL
Java_com_hermes_analyzer_utils_NativeBridge_disassembleNative(
        JNIEnv* env, jobject thiz, jbyteArray bytes, jlong baseAddr, jstring arch) {
    jsize len = env->GetArrayLength(bytes);
    jbyte* data = env->GetByteArrayElements(bytes, nullptr);

    csh handle;
    cs_arch csArch = CS_ARCH_ARM64;
    cs_mode csMode = CS_MODE_LITTLE_ENDIAN;

    const char* archStr = env->GetStringUTFChars(arch, nullptr);
    if (strcmp(archStr, "arm64") == 0) { csArch = CS_ARCH_ARM64; csMode = CS_MODE_LITTLE_ENDIAN; }
    else if (strcmp(archStr, "arm") == 0) { csArch = CS_ARCH_ARM; csMode = CS_MODE_LITTLE_ENDIAN; }
    else if (strcmp(archStr, "x86") == 0) { csArch = CS_ARCH_X86; csMode = CS_MODE_32; }
    else if (strcmp(archStr, "x64") == 0) { csArch = CS_ARCH_X86; csMode = CS_MODE_64; }
    env->ReleaseStringUTFChars(arch, archStr);

    std::string result;
    if (cs_open(csArch, csMode, &handle) == CS_ERR_OK) {
        cs_insn* insn;
        size_t count = cs_disasm(handle, (uint8_t*)data, len, baseAddr, 0, &insn);
        for (size_t i = 0; i < count; i++) {
            char line[256];
            snprintf(line, sizeof(line), "0x%llX: %s %s\n", 
                     insn[i].address, insn[i].mnemonic, insn[i].op_str);
            result += line;
        }
        cs_free(insn, count);
        cs_close(&handle);
    }

    env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_hermes_analyzer_utils_NativeBridge_getBinaryInfoNative(
        JNIEnv* env, jobject thiz, jstring path) {
    const char* filePath = env->GetStringUTFChars(path, nullptr);

    FILE* f = fopen(filePath, "rb");
    std::string info = "{"type":"unknown"}";

    if (f) {
        uint8_t magic[4];
        fread(magic, 1, 4, f);

        if (magic[0] == 0x7F && magic[1] == 'E' && magic[2] == 'L' && magic[3] == 'F') {
            uint8_t ei_class;
            fread(&ei_class, 1, 1, f);
            uint16_t e_machine;
            fseek(f, 18, SEEK_SET);
            fread(&e_machine, 2, 1, f);

            char buf[512];
            const char* machine = "unknown";
            switch (e_machine) {
                case 0x28: machine = "ARM"; break;
                case 0xB7: machine = "ARM64"; break;
                case 0x03: machine = "x86"; break;
                case 0x3E: machine = "x86_64"; break;
            }
            snprintf(buf, sizeof(buf), 
                "{"type":"elf","class":"%s","machine":"%s"}",
                ei_class == 1 ? "ELF32" : "ELF64", machine);
            info = buf;
        }
        else if (magic[0] == 'd' && magic[1] == 'e' && magic[2] == 'x') {
            info = "{"type":"dex"}";
        }
        else if (magic[0] == 'P' && magic[1] == 'K') {
            info = "{"type":"apk"}";
        }

        fclose(f);
    }

    env->ReleaseStringUTFChars(path, filePath);
    return env->NewStringUTF(info.c_str());
}
