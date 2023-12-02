package com.lapissea.dfs.core.versioning;

import com.lapissea.dfs.type.IOInstance;

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
	public To apply(IOInstance instance){
		//noinspection unchecked
		return fn.apply(unpack(instance));
	}
	
	private <T extends IOInstance<T>> UnpackedInstance<?> unpack(T instance){
		return new UnpackedInstance<>(instance.getThisStruct(), instance);
	}
}
