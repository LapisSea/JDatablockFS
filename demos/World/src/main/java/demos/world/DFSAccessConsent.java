package demos.world;

import com.lapissea.dfs.type.IOInstance;

import java.lang.invoke.MethodHandles;

public final class DFSAccessConsent{
	
	static{ IOInstance.allowFullAccess(MethodHandles.lookup()); }
}
