package org.amnezia.awg;

/**
 * JNI обёртка для AmneziaWG нативной библиотеки (libwg-go.so)
 *
 * ВАЖНО: Пакет org.amnezia.awg ОБЯЗАТЕЛЕН — JNI binding в нативной
 * библиотеке привязан к этому пакету (Java_org_amnezia_awg_GoBackend_*).
 *
 * Библиотека встроена в APK (jniLibs/) — не требует внешних приложений.
 */
public class GoBackend {

    static {
        System.loadLibrary("wg-go");
    }

    /**
     * Создать и запустить AWG/WG туннель
     *
     * @param ifName   имя интерфейса (напр. "awg0")
     * @param tunFd    файловый дескриптор TUN устройства от VpnService
     * @param settings конфиг в UAPI формате (private_key=hex, jc=4, ...)
     * @return handle туннеля (>0 = успех, -1 = ошибка)
     */
    public static native int awgTurnOn(String ifName, int tunFd, String settings);

    /**
     * Остановить AWG/WG туннель
     *
     * @param handle хэндл туннеля из awgTurnOn
     */
    public static native void awgTurnOff(int handle);

    /**
     * Получить текущий конфиг активного туннеля в UAPI формате
     */
    public static native String awgGetConfig(int handle);

    /**
     * Получить IPv4 socket fd туннеля
     */
    public static native int awgGetSocketV4(int handle);

    /**
     * Получить IPv6 socket fd туннеля
     */
    public static native int awgGetSocketV6(int handle);

    /**
     * Получить версию AmneziaWG библиотеки
     */
    public static native String awgVersion();
}
