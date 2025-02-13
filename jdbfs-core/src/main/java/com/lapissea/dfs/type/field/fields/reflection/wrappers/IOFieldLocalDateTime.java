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
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.fields.reflection.IOFieldWrapper;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

public final class IOFieldLocalDateTime<CTyp extends IOInstance<CTyp>> extends IOFieldWrapper<CTyp, LocalDateTime>{
	
	@SuppressWarnings("unused")
	private static final class Usage extends FieldUsage.InstanceOf<LocalDateTime>{
		public Usage(){ super(LocalDateTime.class, Set.of(IOFieldLocalDateTime.class)); }
		@Override
		public <T extends IOInstance<T>> IOField<T, LocalDateTime> create(FieldAccessor<T> field){
			return new IOFieldLocalDateTime<>(field);
		}
		@Override
		public <T extends IOInstance<T>> List<Behaviour<?, T>> annotationBehaviour(Class<IOField<T, ?>> fieldType){
			return List.of(Behaviour.of(IONullability.class, BehaviourSupport::ioNullability));
		}
	}
	
	static{ Preload.preloadFn(IOLocalDateTime.class, "of", LocalDateTime.of(LocalDate.EPOCH, LocalTime.MIDNIGHT)); }
	
	private static MethodHandle CONSTR;
	
	@IOInstance.Order({"date", "time"})
	private interface IOLocalDateTime extends IOInstance.Def<IOLocalDateTime>{
		
		static String toString(IOLocalDateTime val){
			return val.getData().toString();
		}
		
		LocalDate date();
		LocalTime time();
		
		Struct<IOLocalDateTime> STRUCT = Struct.of(IOLocalDateTime.class);
		
		private static MethodHandle init(){
			return CONSTR = Def.constrRef(IOLocalDateTime.class, LocalDate.class, LocalTime.class);
		}
		
		static IOLocalDateTime of(LocalDateTime val){
			var c = CONSTR;
			if(c == null) c = init();
			try{
				return (IOLocalDateTime)c.invoke(val.toLocalDate(), val.toLocalTime());
			}catch(Throwable e){
				throw new RuntimeException(e);
			}
		}
		default LocalDateTime getData(){
			return LocalDateTime.of(date(), time());
		}
	}
	
	private final StructPipe<IOLocalDateTime> instancePipe;
	
	public IOFieldLocalDateTime(FieldAccessor<CTyp> accessor){ this(accessor, null); }
	public IOFieldLocalDateTime(FieldAccessor<CTyp> accessor, VaryingSize.Provider varProvider){
		super(accessor);
		
		if(varProvider != null){
			instancePipe = FixedVaryingStructPipe.tryVarying(IOLocalDateTime.STRUCT, varProvider);
		}else instancePipe = StandardStructPipe.of(IOLocalDateTime.STRUCT);
		
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
	
	private IOLocalDateTime getWrapped(VarPool<CTyp> ioPool, CTyp instance){
		var raw = get(ioPool, instance);
		if(raw == null) return null;
		return IOLocalDateTime.of(raw);
	}
	
	private static final LocalDateTime DEFAULT = LocalDateTime.of(LocalDate.EPOCH, LocalTime.MIDNIGHT);
	
	@Override
	protected LocalDateTime defaultValue(){ return DEFAULT; }
	
	@Override
	protected IOField<CTyp, LocalDateTime> maxAsFixedSize(VaryingSize.Provider varProvider){
		return new IOFieldLocalDateTime<>(getAccessor(), varProvider);
	}
	
	@Override
	protected void writeValue(DataProvider provider, ContentWriter dest, LocalDateTime value) throws IOException{
		instancePipe.write(provider, dest, IOLocalDateTime.of(value));
	}
	@Override
	protected LocalDateTime readValue(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		return instancePipe.readNew(provider, src, genericContext).getData();
	}
	@Override
	protected void skipValue(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		instancePipe.skip(provider, src, genericContext);
	}
}
