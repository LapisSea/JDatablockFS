package com.lapissea.dfs.benchmark;

import com.lapissea.dfs.type.IOInstance;

import java.lang.invoke.MethodHandles;

/**
 * Module-wide access consent for JDatablockFS.run, found by the convention walk from the benchmark package.
 * The walk from {@code com.lapissea.dfs.benchmark} never reaches {@code com.lapissea.dfs.run.DFSAccessConsent}
 */
public final class DFSAccessConsent{

	static{ IOInstance.allowFullAccess(MethodHandles.lookup()); }

}
