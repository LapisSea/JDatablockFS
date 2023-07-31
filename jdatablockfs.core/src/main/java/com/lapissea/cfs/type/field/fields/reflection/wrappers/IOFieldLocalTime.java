package com.lapissea.cfs.type.field.fields.reflection.wrappers;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.bit.FlagReader;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.VaryingSize;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.fields.NullFlagCompanyField;
import com.lapissea.cfs.utils.IOUtils;

import java.io.IOException;
import java.time.LocalTime;
import java.util.Objects;

public final class IOFieldLocalTime<CTyp extends IOInstance<CTyp>> extends NullFlagCompanyField<CTyp, LocalTime>{
	
	@SuppressWarnings("unused")
	private static final class Usage extends FieldUsage.InstanceOf<LocalTime>{
		public Usage(){ super(LocalTime.class); }
		@Override
		public <T extends IOInstance<T>> IOField<T, LocalTime> create(FieldAccessor<T> field){
			return new IOFieldLocalTime<>(field);
		}
	}
	
	private final VaryingSize varSize;
	
	public IOFieldLocalTime(FieldAccessor<CTyp> accessor){ this(accessor, null); }
	public IOFieldLocalTime(FieldAccessor<CTyp> accessor, VaryingSize.Provider varProvider){
		super(accessor);
		this.varSize = varProvider != null? varProvider.provide(NumberSize.LONG, null, false) : null;
		
		if(varSize != null){
			initSizeDescriptor(SizeDescriptor.Fixed.of(varSize.size.bytes));
		}else{
			initSizeDescriptor(SizeDescriptor.Unknown.of(
				WordSpace.BYTE,
				0,
				NumberSize.LONG.optionalBytesLong,
				(ioPool, prov, inst) -> {
					var val = get(ioPool, inst);
					if(val == null){
						if(!nullable()) throw new NullPointerException();
						return 0;
					}
					var siz = NumberSize.bySizeSigned(val.toNanoOfDay());
					return 1 + siz.bytes;
				}
			));
		}
	}
	
	@Override
	public LocalTime get(VarPool<CTyp> ioPool, CTyp instance){
		return getNullable(ioPool, instance, () -> LocalTime.MIN);
	}
	@Override
	public boolean isNull(VarPool<CTyp> ioPool, CTyp instance){
		return isNullRawNullable(ioPool, instance);
	}
	
	@Override
	public void set(VarPool<CTyp> ioPool, CTyp instance, LocalTime value){
		super.set(ioPool, instance, switch(getNullability()){
			case DEFAULT_IF_NULL, NULLABLE -> value;
			case NOT_NULL -> Objects.requireNonNull(value);
		});
	}
	@Override
	protected IOField<CTyp, LocalTime> maxAsFixedSize(VaryingSize.Provider varProvider){
		return new IOFieldLocalTime<>(getAccessor(), varProvider);
	}
	
	@Override
	public void write(VarPool<CTyp> ioPool, DataProvider provider, ContentWriter dest, CTyp instance) throws IOException{
		var val = get(ioPool, instance);
		if(nullable()){
			if(val == null){
				if(varSize != null){
					IOUtils.zeroFill(dest::write, (int)getSizeDescriptor().requireFixed(WordSpace.BYTE));
				}
				return;
			}
		}
		dest.writeInt8Dynamic(val.toNanoOfDay());
	}
	
	private LocalTime readNew(VarPool<CTyp> ioPool, ContentReader src, CTyp instance) throws IOException{
		if(nullable()){
			boolean isNull = getIsNull(ioPool, instance);
			if(isNull){
				if(varSize != null){
					src.skipExact((int)getSizeDescriptor().requireFixed(WordSpace.BYTE));
				}
				return null;
			}
		}
		return LocalTime.ofNanoOfDay(src.readInt8Dynamic());
	}
	
	@Override
	public void read(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		set(ioPool, instance, readNew(ioPool, src, instance));
	}
	
	@Override
	public void skip(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		if(src.optionallySkipExact(getSizeDescriptor().getFixed(WordSpace.BYTE))){
			return;
		}
		
		if(nullable()){
			boolean isNull = getIsNull(ioPool, instance);
			if(isNull){
				if(varSize != null){
					src.skipExact((int)getSizeDescriptor().requireFixed(WordSpace.BYTE));
				}
				return;
			}
		}
		NumberSize size = FlagReader.readSingle(src, NumberSize.FLAG_INFO);
		size.skip(src);
	}
}
