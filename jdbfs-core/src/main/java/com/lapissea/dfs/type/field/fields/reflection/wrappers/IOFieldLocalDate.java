package com.lapissea.dfs.type.field.fields.reflection.wrappers;

import com.lapissea.dfs.core.DataProvider;
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
import com.lapissea.dfs.type.field.fields.reflection.IOFieldWrapper;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public final class IOFieldLocalDate<CTyp extends IOInstance<CTyp>> extends IOFieldWrapper<CTyp, LocalDate>{
	
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
		this.varSize = varProvider != null? varProvider.provide(NumberSize.LONG.bytes, null, false) : null;
		
		if(varSize != null){
			varSize.requireNumSize();
			initSizeDescriptor(SizeDescriptor.Fixed.of(varSize.size));
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
	protected LocalDate defaultValue(){ return LocalDate.EPOCH; }
	
	@Override
	protected IOField<CTyp, LocalDate> maxAsFixedSize(VaryingSize.Provider varProvider){
		return new IOFieldLocalDate<>(getAccessor(), varProvider);
	}
	
	@Override
	protected void writeValue(DataProvider provider, ContentWriter dest, LocalDate value) throws IOException{
		var val = value.toEpochDay();
		if(varSize != null){
			varSize.safeNumber(val).write(dest, val);
		}else{
			dest.writeInt8Dynamic(val);
		}
	}
	@Override
	protected LocalDate readValue(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		long day;
		if(varSize != null){
			day = varSize.nSize.read(src);
		}else{
			day = src.readInt8Dynamic();
		}
		return LocalDate.ofEpochDay(day);
	}
	@Override
	protected void skipValue(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		NumberSize size;
		if(varSize != null){
			size = varSize.nSize;
		}else{
			size = FlagReader.readSingle(src, NumberSize.FLAG_INFO);
		}
		size.skip(src);
	}
}
