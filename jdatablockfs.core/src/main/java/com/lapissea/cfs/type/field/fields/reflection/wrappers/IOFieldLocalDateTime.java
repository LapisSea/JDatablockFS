package com.lapissea.cfs.type.field.fields.reflection.wrappers;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.instancepipe.FixedVaryingStructPipe;
import com.lapissea.cfs.io.instancepipe.StandardStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.VaryingSize;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.fields.NullFlagCompanyField;
import com.lapissea.cfs.utils.IOUtils;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Objects;

public class IOFieldLocalDateTime<CTyp extends IOInstance<CTyp>> extends NullFlagCompanyField<CTyp, LocalDateTime>{
	
	@SuppressWarnings("unused")
	private static final class Usage extends FieldUsage.InstanceOf<LocalDateTime>{
		public Usage(){ super(LocalDateTime.class); }
		@Override
		public <T extends IOInstance<T>> IOField<T, LocalDateTime> create(FieldAccessor<T> field, GenericContext genericContext){
			return new IOFieldLocalDateTime<>(field);
		}
	}
	
	@IOInstance.Def.Order({"date", "time"})
	private interface IOLocalDateTime extends IOInstance.Def<IOLocalDateTime>{
		
		static String toString(IOLocalDateTime val){
			return val.getData().toString();
		}
		
		LocalDate date();
		LocalTime time();
		
		Struct<IOLocalDateTime> STRUCT = Struct.of(IOLocalDateTime.class);
		
		MethodHandle CONSTR = Def.constrRef(IOLocalDateTime.class, LocalDate.class, LocalTime.class);
		static IOLocalDateTime of(LocalDateTime val){
			try{
				return (IOLocalDateTime)CONSTR.invoke(val.toLocalDate(), val.toLocalTime());
			}catch(Throwable e){
				throw new RuntimeException(e);
			}
		}
		default LocalDateTime getData(){
			return LocalDateTime.of(date(), time());
		}
	}
	
	private final StructPipe<IOLocalDateTime> instancePipe;
	private final boolean                     fixed;
	
	public IOFieldLocalDateTime(FieldAccessor<CTyp> accessor){ this(accessor, null); }
	public IOFieldLocalDateTime(FieldAccessor<CTyp> accessor, VaryingSize.Provider varProvider){
		super(accessor);
		this.fixed = varProvider != null;
		
		if(fixed){
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
	
	@Override
	public LocalDateTime get(VarPool<CTyp> ioPool, CTyp instance){
		return getNullable(ioPool, instance, () -> LocalDateTime.of(LocalDate.EPOCH, LocalTime.MIDNIGHT));
	}
	@Override
	public boolean isNull(VarPool<CTyp> ioPool, CTyp instance){
		return isNullRawNullable(ioPool, instance);
	}
	
	@Override
	public void set(VarPool<CTyp> ioPool, CTyp instance, LocalDateTime value){
		super.set(ioPool, instance, switch(getNullability()){
			case DEFAULT_IF_NULL, NULLABLE -> value;
			case NOT_NULL -> Objects.requireNonNull(value);
		});
	}
	@Override
	protected IOField<CTyp, LocalDateTime> maxAsFixedSize(VaryingSize.Provider varProvider){
		return new IOFieldLocalDateTime<>(getAccessor(), varProvider);
	}
	
	@Override
	public void write(VarPool<CTyp> ioPool, DataProvider provider, ContentWriter dest, CTyp instance) throws IOException{
		var val = getWrapped(ioPool, instance);
		if(nullable()){
			if(val == null){
				if(fixed){
					IOUtils.zeroFill(dest::write, (int)getSizeDescriptor().requireFixed(WordSpace.BYTE));
				}
				return;
			}
		}
		instancePipe.write(provider, dest, val);
	}
	
	private LocalDateTime readNew(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		if(nullable()){
			boolean isNull = getIsNull(ioPool, instance);
			if(isNull){
				if(fixed){
					src.skipExact((int)getSizeDescriptor().requireFixed(WordSpace.BYTE));
				}
				return null;
			}
		}
		
		return instancePipe.readNew(provider, src, genericContext).getData();
	}
	
	@Override
	public void read(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		set(ioPool, instance, readNew(ioPool, provider, src, instance, genericContext));
	}
	
	@Override
	public void skip(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		if(src.optionallySkipExact(getSizeDescriptor().getFixed(WordSpace.BYTE))){
			return;
		}
		
		if(nullable()){
			boolean isNull = getIsNull(ioPool, instance);
			if(isNull){
				if(fixed){
					src.skipExact((int)getSizeDescriptor().requireFixed(WordSpace.BYTE));
				}
				return;
			}
		}
		instancePipe.skip(provider, src, genericContext);
	}
}
