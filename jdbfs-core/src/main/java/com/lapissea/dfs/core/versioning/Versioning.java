package com.lapissea.dfs.core.versioning;

import com.lapissea.util.NotImplementedException;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class Versioning{
	
	public static final Versioning JUST_FAIL = new Versioning(Set.of(), List.of());
	
	private final Set<VersioningOptions>      options;
	private final List<VersionTransformer<?>> transformers;
	
	public Versioning(Set<VersioningOptions> options, List<VersionTransformer<?>> transformers){
		this.options = Collections.unmodifiableSet(
			options.isEmpty()? EnumSet.noneOf(VersioningOptions.class) : EnumSet.copyOf(options)
		);
		
		this.transformers = transformers;
	}
	
	
	public VersionTransformer<?> createTransformer(ClassVersionDiff diff){
		
		
		throw new NotImplementedException();//TODO
	}
}
