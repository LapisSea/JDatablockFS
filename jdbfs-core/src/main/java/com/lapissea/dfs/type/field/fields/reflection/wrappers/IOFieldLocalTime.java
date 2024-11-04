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
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

public final class IOFieldLocalTime<CTyp extends IOInstance<CTyp>> extends IOFieldWrapper<CTyp, LocalTime>{
	
	@SuppressWarnings("unused")
	private static final class Usage extends FieldUsage.InstanceOf<LocalTime>{
		public Usage(){ super(LocalTime.class, Set.of(IOFieldLocalTime.class)); }
		@Override
		public <T extends IOInstance<T>> IOField<T, LocalTime> create(FieldAccessor<T> field){
			return new IOFieldLocalTime<>(field);
		}
		@Override
		public <T extends IOInstance<T>> List<Behaviour<?, T>> annotationBehaviour(Class<IOField<T, ?>> fieldType){
			return List.of(Behaviour.of(IONullability.class, BehaviourSupport::ioNullability));
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
	protected LocalTime defaultValue(){ return LocalTime.MIN; }
	
	@Override
	protected IOField<CTyp, LocalTime> maxAsFixedSize(VaryingSize.Provider varProvider){
		return new IOFieldLocalTime<>(getAccessor(), varProvider);
	}
	
	@Override
	protected void writeValue(DataProvider provider, ContentWriter dest, LocalTime value) throws IOException{
		if(varSize != null){
			varSize.size.write(dest, value.toNanoOfDay());
		}else{
			dest.writeInt8Dynamic(value.toNanoOfDay());
		}
	}
	@Override
	protected LocalTime readValue(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		long nanos;
		if(varSize != null){
			nanos = varSize.size.read(src);
		}else{
			nanos = src.readInt8Dynamic();
		}
		return LocalTime.ofNanoOfDay(nanos);
	}
	@Override
	protected void skipValue(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		NumberSize size;
		if(varSize != null){
			size = varSize.size;
		}else{
			size = FlagReader.readSingle(src, NumberSize.FLAG_INFO);
		}
		size.skip(src);
	}
}
