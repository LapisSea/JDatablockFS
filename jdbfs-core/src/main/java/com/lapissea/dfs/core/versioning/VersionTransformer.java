package com.lapissea.dfs.core.versioning;

import com.lapissea.dfs.type.IOInstance;

import java.util.Objects;
import java.util.function.Function;

public class VersionTransformer<To extends IOInstance<To>> implements Function<IOInstance<?>, To>{
	
	public final  String                         oldClassName;
	public final  String                         newClassName;
	public final  String                         transformReport;
	private final Function<UnpackedInstance, To> transformer;
	
	protected VersionTransformer(String newClassName, String transformReport, Function<UnpackedInstance, To> transformer){
		this(newClassName, null, transformReport, transformer);
	}
	private VersionTransformer(String newClassName, String oldClassName, String transformReport, Function<UnpackedInstance, To> transformer){
		this.newClassName = Objects.requireNonNull(newClassName);
		this.oldClassName = oldClassName;
		this.transformReport = transformReport == null? "Transformation of: " + newClassName : transformReport;
		this.transformer = Objects.requireNonNull(transformer);
	}
	
	public VersionTransformer<To> withOldName(String oldClassName){
		Objects.requireNonNull(oldClassName);
		return new VersionTransformer<>(newClassName, oldClassName, transformReport, transformer);
	}
	
	public String getOldClassName(){
		return oldClassName;
	}
	
	@Override
	public To apply(IOInstance instance){
		//noinspection unchecked
		return transformer.apply(unpack(instance));
	}
	
	private <T extends IOInstance<T>> UnpackedInstance unpack(T instance){
		return new UnpackedInstance(instance.getThisStruct(), instance);
	}
	
	@Override
	public String toString(){
		return newClassName;
	}
}
