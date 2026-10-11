package consentwalk.bad;

import com.lapissea.dfs.type.IOInstance;

import java.lang.invoke.MethodHandles;

/**
 * An unsafe anchor: it exposes the full-access lookup in a field, so Access must refuse it.
 */
public final class DFSAccessConsent{
	
	public static final MethodHandles.Lookup LEAK = MethodHandles.lookup();
	
	static{ IOInstance.allowFullAccess(MethodHandles.lookup()); }
	
}
