package com.lapissea.dfs.access;

/**
 * A module-wide access consent anchor, declared as a service provider:
 * <pre>{@code
 * provides com.lapissea.dfs.access.AccessConsent with com.example.MyAccessConsent;
 * }</pre>
 * The provider grants access from its static initializer. Example:
 * <pre>
 * public final class DFSAccessConsent implements AccessConsent{
 *     static{ IOInstance.allowFullAccess(MethodHandles.lookup()); }
 * }
 * </pre>
 */
public interface AccessConsent{
}
