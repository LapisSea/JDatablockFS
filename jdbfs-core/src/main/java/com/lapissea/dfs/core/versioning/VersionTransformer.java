package com.lapissea.dfs.core.versioning;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.util.NotImplementedException;

import java.util.Objects;
import java.util.function.Function;

public class VersionTransformer<To extends IOInstance<To>> implements Function<IOInstance<?>, To>{
	
	public final  String                            matchingClassName;
	private final Function<UnpackedInstance<?>, To> fn;
	
	protected VersionTransformer(String matchingClassName, Function<UnpackedInstance<?>, To> transformer){
		this.matchingClassName = Objects.requireNonNull(matchingClassName);
		this.fn = Objects.requireNonNull(transformer);
	}
	
	@Override
	public To apply(IOInstance<?> instance){
		return fn.apply(unpack(instance));
	}
	
	private UnpackedInstance<?> unpack(IOInstance<?> instance){
		throw new NotImplementedException();//TODO
	}
}
