package com.lapissea.dfs.type.compilation.helpers;

import com.lapissea.dfs.type.IOInstance;

/**
 * A class intended for internal use on types that have final field(s) and can not be directly written in to
 */
public abstract class ProxyBuilder<Actual extends IOInstance<Actual>> extends IOInstance.Managed<ProxyBuilder<Actual>>{
	public abstract Actual build();
}
