package com.lapissea.dfs.type.field.fields.reflection.wrappers;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.io.instancepipe.FixedVaryingStructPipe;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.BehaviourSupport;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.type.field.VaryingSize;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.type.field.fields.reflection.IOFieldWrapper;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.time.Duration;
import java.util.List;
import java.util.Set;

public final class IOFieldDuration<CTyp extends IOInstance<CTyp>> extends IOFieldWrapper<CTyp, Duration>{
	
	@SuppressWarnings("unused")
	private static final class Usage extends FieldUsage.InstanceOf<Duration>{
		public Usage(){ super(Duration.class, Set.of(IOFieldDuration.class)); }
		@Override
		public <T extends IOInstance<T>> IOField<T, Duration> create(FieldAccessor<T> field){
			return new IOFieldDuration<>(field);
		}
		@Override
		public <T extends IOInstance<T>> List<Behaviour<?, T>> annotationBehaviour(Class<IOField<T, ?>> fieldType){
			return List.of(Behaviour.of(IONullability.class, BehaviourSupport::ioNullability));
		}
	}
	
	@IOInstance.Order({"seconds", "nanos"})
	private interface IODuration extends IOInstance.Def<IODuration>{
		
		static String toString(IODuration val){
			return val.getData().toString();
		}
		
		@IODependency.VirtualNumSize
		long seconds();
		@IODependency.VirtualNumSize
		@IOValue.Unsigned
		int nanos();
		
		Struct<IODuration> STRUCT = Struct.of(IODuration.class);
		
		MethodHandle CONSTR = IOInstance.Def.constrRef(IODuration.class, long.class, int.class);
		static IODuration of(Duration val){
			try{
				return (IODuration)CONSTR.invoke(val.getSeconds(), val.getNano());
			}catch(Throwable e){
				throw new RuntimeException(e);
			}
		}
		default Duration getData(){
			return Duration.ofSeconds(seconds(), nanos());
		}
	}
	
	private final StructPipe<IODuration> instancePipe;
	private final boolean                fixed;
	
	public IOFieldDuration(FieldAccessor<CTyp> accessor){ this(accessor, null); }
	public IOFieldDuration(FieldAccessor<CTyp> accessor, VaryingSize.Provider varProvider){
		super(accessor);
		this.fixed = varProvider != null;
		
		if(fixed){
			instancePipe = FixedVaryingStructPipe.tryVarying(IODuration.STRUCT, varProvider);
		}else instancePipe = StandardStructPipe.of(IODuration.STRUCT);
		
		var desc = instancePipe.getSizeDescriptor();
		
		var fixedSiz = desc.getFixed();
		if(fixedSiz.isPresent()){
			initSizeDescriptor(SizeDescriptor.Fixed.of(desc.getWordSpace(), fixedSiz.getAsLong()));
		}else{
			initSizeDescriptor(SizeDescriptor.Unknown.of(
				desc.getWordSpace(),
				nullable()? 0 : desc.getMin(),
				desc.getMax(),
				(ioPool, prov, inst) -> {
					var val = getWrapped(ioPool, inst);
					if(val == null){
						if(!nullable()) throw new NullPointerException();
						return 0;
					}
					return desc.calcUnknown(instancePipe.makeIOPool(), prov, val, desc.getWordSpace());
				}
			));
		}
	}
	
	private IODuration getWrapped(VarPool<CTyp> ioPool, CTyp instance){
		var raw = get(ioPool, instance);
		if(raw == null) return null;
		return IODuration.of(raw);
	}
	
	@Override
	protected Duration defaultValue(){ return Duration.ZERO; }
	
	@Override
	protected IOField<CTyp, Duration> maxAsFixedSize(VaryingSize.Provider varProvider){
		return new IOFieldDuration<>(getAccessor(), varProvider);
	}
	
	@Override
	protected void writeValue(DataProvider provider, ContentWriter dest, Duration value) throws IOException{
		instancePipe.write(provider, dest, IODuration.of(value));
	}
	@Override
	protected Duration readValue(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		return instancePipe.readNew(provider, src, genericContext).getData();
	}
	@Override
	protected void skipValue(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		instancePipe.skip(provider, src, genericContext);
	}
}
