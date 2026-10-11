package com.lapissea.dfs;

import com.lapissea.dfs.access.AccessConsent;
import com.lapissea.dfs.type.IOInstance;

import java.lang.invoke.MethodHandles;

/**
 * Module-wide access consent for JDatablockFS.core
 */
public final class DFSAccessConsent implements AccessConsent{
	
	static{ IOInstance.allowFullAccess(MethodHandles.lookup()); }
	
}
