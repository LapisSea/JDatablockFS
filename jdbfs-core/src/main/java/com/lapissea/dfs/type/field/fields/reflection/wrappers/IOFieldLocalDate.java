package com.lapissea.dfs.type.field.fields.reflection.wrappers;

import com.lapissea.dfs.chunk.DataProvider;
import com.lapissea.dfs.io.bit.FlagReader;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.BehaviourSupport;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.type.field.VaryingSize;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.fields.NullFlagCompanyField;
import com.lapissea.dfs.utils.IOUtils;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class IOFieldLocalDate<CTyp extends IOInstance<CTyp>> extends NullFlagCompanyField<CTyp, LocalDate>{
	
	@SuppressWarnings("unused")
	private static final class Usage extends FieldUsage.InstanceOf<LocalDate>{
		public Usage(){ super(LocalDate.class, Set.of(IOFieldLocalDate.class)); }
		@Override
		public <T extends IOInstance<T>> IOField<T, LocalDate> create(FieldAccessor<T> field){
			return new IOFieldLocalDate<>(field);
		}
		@Override
		public <T extends IOInstance<T>> List<Behaviour<?, T>> annotationBehaviour(Class<IOField<T, ?>> fieldType){
			return List.of(Behaviour.of(IONullability.class, BehaviourSupport::ioNullability));
		}
	}
	
	private final VaryingSize varSize;
	
	public IOFieldLocalDate(FieldAccessor<CTyp> accessor){ this(accessor, null); }
	public IOFieldLocalDate(FieldAccessor<CTyp> accessor, VaryingSize.Provider varProvider){
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
					var siz = NumberSize.bySizeSigned(val.toEpochDay());
					return 1 + siz.bytes;
				}
			));
		}
	}
	
	@Override
	public LocalDate get(VarPool<CTyp> ioPool, CTyp instance){
		return getNullable(ioPool, instance, () -> LocalDate.EPOCH);
	}
	@Override
	public boolean isNull(VarPool<CTyp> ioPool, CTyp instance){
		return isNullRawNullable(ioPool, instance);
	}
	
	@Override
	public void set(VarPool<CTyp> ioPool, CTyp instance, LocalDate value){
		super.set(ioPool, instance, switch(getNullability()){
			case DEFAULT_IF_NULL, NULLABLE -> value;
			case NOT_NULL -> Objects.requireNonNull(value);
		});
	}
	@Override
	protected IOField<CTyp, LocalDate> maxAsFixedSize(VaryingSize.Provider varProvider){
		return new IOFieldLocalDate<>(getAccessor(), varProvider);
	}
	
	@Override
	public void write(VarPool<CTyp> ioPool, DataProvider provider, ContentWriter dest, CTyp instance) throws IOException{
		var val = get(ioPool, instance);
		if(nullable()){
			if(val == null){
				if(varSize != null){
					IOUtils.zeroFill(dest, (int)getSizeDescriptor().requireFixed(WordSpace.BYTE));
				}
				return;
			}
		}
		dest.writeInt8Dynamic(val.toEpochDay());
	}
	
	private LocalDate readNew(VarPool<CTyp> ioPool, ContentReader src, CTyp instance) throws IOException{
		if(nullable()){
			boolean isNull = getIsNull(ioPool, instance);
			if(isNull){
				if(varSize != null){
					src.skipExact((int)getSizeDescriptor().requireFixed(WordSpace.BYTE));
				}
				return null;
			}
		}
		return LocalDate.ofEpochDay(src.readInt8Dynamic());
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
