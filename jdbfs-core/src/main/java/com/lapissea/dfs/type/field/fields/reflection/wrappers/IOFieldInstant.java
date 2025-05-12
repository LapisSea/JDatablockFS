package com.lapissea.dfs.type.field.fields.reflection.wrappers;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.internal.Preload;
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
import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class IOFieldInstant<CTyp extends IOInstance<CTyp>> extends IOFieldWrapper<CTyp, Instant>{
	
	@SuppressWarnings("unused")
	private static final class Usage extends FieldUsage.InstanceOf<Instant>{
		public Usage(){ super(Instant.class, Set.of(IOFieldInstant.class)); }
		@Override
		public <T extends IOInstance<T>> IOField<T, Instant> create(FieldAccessor<T> field){
			return new IOFieldInstant<>(field);
		}
		@Override
		public <T extends IOInstance<T>> List<Behaviour<?, T>> annotationBehaviour(Class<IOField<T, ?>> fieldType){
			return List.of(Behaviour.of(IONullability.class, BehaviourSupport::ioNullability));
		}
	}
	
	static{ Preload.preloadFn(IOInstant.class, "of", Instant.EPOCH); }
	
	private static MethodHandle CONSTR;
	
	@IOInstance.Order({"seconds", "nanos"})
	private interface IOInstant extends IOInstance.Def<IOInstant>{
		
		static String toString(IOInstant val){
			return val.getData().toString();
		}
		
		@IODependency.VirtualNumSize
		long seconds();
		@IODependency.VirtualNumSize
		@IOValue.Unsigned
		int nanos();
		
		Struct<IOInstant> STRUCT = Struct.of(IOInstant.class);
		
		private static MethodHandle init(){
			return CONSTR = Def.constrRef(IOInstant.class, long.class, int.class);
		}
		
		static IOInstant of(Instant val){
			var c = CONSTR;
			if(c == null) c = init();
			try{
				return (IOInstant)c.invoke(val.getEpochSecond(), val.getNano());
			}catch(Throwable e){
				throw new RuntimeException(e);
			}
		}
		default Instant getData(){
			return Instant.ofEpochSecond(seconds(), nanos());
		}
	}
	
	private final StructPipe<IOInstant> instancePipe;
	
	public IOFieldInstant(FieldAccessor<CTyp> accessor){ this(accessor, null); }
	public IOFieldInstant(FieldAccessor<CTyp> accessor, VaryingSize.Provider varProvider){
		super(accessor);
		boolean fixed = varProvider != null;
		
		if(fixed){
			instancePipe = FixedVaryingStructPipe.tryVarying(IOInstant.STRUCT, varProvider);
		}else instancePipe = StandardStructPipe.of(IOInstant.STRUCT);
		
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
					var val = get(ioPool, inst);
					if(val == null){
						if(!nullable()) throw new NullPointerException();
						return 0;
					}
					return desc.calcUnknown(instancePipe.makeIOPool(), prov, IOInstant.of(val), desc.getWordSpace());
				}
			));
		}
	}
	
	@Override
	protected Instant defaultValue(){ return Instant.EPOCH; }
	
	@Override
	protected IOField<CTyp, Instant> maxAsFixedSize(VaryingSize.Provider varProvider){
		return new IOFieldInstant<>(getAccessor(), varProvider);
	}
	
	@Override
	protected void writeValue(DataProvider provider, ContentWriter dest, Instant value) throws IOException{
		instancePipe.write(provider, dest, IOInstant.of(value));
	}
	@Override
	protected Instant readValue(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		return instancePipe.readNew(provider, src, genericContext).getData();
	}
	@Override
	protected void skipValue(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		instancePipe.skip(provider, src, genericContext);
	}
}
