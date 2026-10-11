package consentwalk.good;

import com.lapissea.dfs.type.IOInstance;

import java.lang.invoke.MethodHandles;

/**
 * A valid consent anchor (no fields or methods). Found via the DFSAccessConsent convention
 */
public final class DFSAccessConsent{
	
	static{ IOInstance.allowFullAccess(MethodHandles.lookup()); }
	
}
