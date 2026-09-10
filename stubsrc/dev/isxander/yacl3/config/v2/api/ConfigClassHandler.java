package dev.isxander.yacl3.config.v2.api;

/**
 * Compile-only stub of YACL's config handler - the two members Colonist Errands calls on
 * {@code McTalkingConfig.INSTANCE} to re-read Talking Colonists' config from disk. The real
 * interface is on the runtime classpath (Talking Colonists depends on YACL). Not packed into the jar.
 */
public interface ConfigClassHandler<T> {

    /** The live config object. */
    T instance();

    /** Re-read the config file into {@link #instance()}; false when the file could not be parsed. */
    boolean load();
}
