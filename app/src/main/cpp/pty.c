// A pseudo-terminal for the sandbox.
//
// Claude Code's login reads its authorization code from a terminal in raw mode. Handed a plain
// pipe it never reads stdin at all, so anything typed into the app went nowhere. forkpty gives
// the child a real controlling terminal, which is what it's waiting for.

#include <jni.h>
#include <pty.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static char **to_c_array(JNIEnv *env, jobjectArray arr) {
    if (arr == NULL) return NULL;
    jsize n = (*env)->GetArrayLength(env, arr);
    char **out = calloc(n + 1, sizeof(char *));
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, arr, i);
        const char *c = (*env)->GetStringUTFChars(env, s, NULL);
        out[i] = strdup(c);
        (*env)->ReleaseStringUTFChars(env, s, c);
        (*env)->DeleteLocalRef(env, s);
    }
    out[n] = NULL;
    return out;
}

static void free_c_array(char **a) {
    if (!a) return;
    for (char **p = a; *p; p++) free(*p);
    free(a);
}

JNIEXPORT jint JNICALL
Java_app_cinder_Pty_spawn(JNIEnv *env, jclass clazz, jobjectArray argvArr,
                                    jobjectArray envpArr, jstring cwdStr, jint rows, jint cols,
                                    jintArray pidOut) {
    char **argv = to_c_array(env, argvArr);
    char **envp = to_c_array(env, envpArr);
    const char *cwd = cwdStr ? (*env)->GetStringUTFChars(env, cwdStr, NULL) : NULL;

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;

    int master = -1;
    pid_t pid = forkpty(&master, NULL, NULL, &ws);
    if (pid < 0) {
        free_c_array(argv);
        free_c_array(envp);
        if (cwd) (*env)->ReleaseStringUTFChars(env, cwdStr, cwd);
        return -1;
    }

    if (pid == 0) {
        // child: it now owns a controlling terminal
        if (cwd) chdir(cwd);
        signal(SIGPIPE, SIG_DFL);
        execve(argv[0], argv, envp);
        _exit(127);
    }

    if (cwd) (*env)->ReleaseStringUTFChars(env, cwdStr, cwd);
    free_c_array(argv);
    free_c_array(envp);

    if (pidOut) {
        jint p = (jint) pid;
        (*env)->SetIntArrayRegion(env, pidOut, 0, 1, &p);
    }
    return master;
}

JNIEXPORT jint JNICALL
Java_app_cinder_Pty_waitFor(JNIEnv *env, jclass clazz, jint pid) {
    int status = 0;
    if (waitpid((pid_t) pid, &status, 0) < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

JNIEXPORT void JNICALL
Java_app_cinder_Pty_killPid(JNIEnv *env, jclass clazz, jint pid) {
    kill((pid_t) pid, SIGHUP);
    kill((pid_t) pid, SIGTERM);
}

JNIEXPORT void JNICALL
Java_app_cinder_Pty_resize(JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    ioctl(fd, TIOCSWINSZ, &ws);
}
