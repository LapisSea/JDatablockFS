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
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.cfs.type.field.fields.NullFlagCompanyField;
import com.lapissea.cfs.utils.IOUtils;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.time.Duration;
import java.util.Objects;

public class IOFieldDuration<CTyp extends IOInstance<CTyp>> extends NullFlagCompanyField<CTyp, Duration>{
	
	@IOInstance.Def.Order({"seconds", "nanos"})
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
	public Duration get(VarPool<CTyp> ioPool, CTyp instance){
		return getNullable(ioPool, instance, () -> Duration.ZERO);
	}
	@Override
	public boolean isNull(VarPool<CTyp> ioPool, CTyp instance){
		return isNullRawNullable(ioPool, instance);
	}
	
	@Override
	public void set(VarPool<CTyp> ioPool, CTyp instance, Duration value){
		super.set(ioPool, instance, switch(getNullability()){
			case DEFAULT_IF_NULL, NULLABLE -> value;
			case NOT_NULL -> Objects.requireNonNull(value);
		});
	}
	@Override
	protected IOField<CTyp, Duration> maxAsFixedSize(VaryingSize.Provider varProvider){
		return new IOFieldDuration<>(getAccessor(), varProvider);
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
	
	private Duration readNew(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
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
