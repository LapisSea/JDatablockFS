package com.lapissea.dfs.run;

import com.lapissea.dfs.type.IOInstance;

import java.lang.invoke.MethodHandles;

/**
 * Module-wide access consent for JDatablockFS.run
 */
public final class DFSAccessConsent{
	
	static{ IOInstance.allowFullAccess(MethodHandles.lookup()); }
	
}
