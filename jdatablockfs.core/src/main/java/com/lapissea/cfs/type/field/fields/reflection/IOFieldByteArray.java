package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.field.BehaviourSupport;
import com.lapissea.cfs.type.field.FieldSet;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.annotations.IOCompression;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.cfs.type.field.fields.NullFlagCompanyField;

import java.io.IOException;
import java.util.List;
import java.util.Set;

public final class IOFieldByteArray<T extends IOInstance<T>> extends NullFlagCompanyField<T, byte[]>{
	
	@SuppressWarnings("unused")
	private static final class Usage extends FieldUsage.InstanceOf<byte[]>{
		public Usage(){ super(byte[].class, Set.of(IOFieldByteArray.class)); }
		@Override
		public <T extends IOInstance<T>> IOField<T, byte[]> create(FieldAccessor<T> field){
			return new IOFieldByteArray<>(field);
		}
		
		@Override
		public <T extends IOInstance<T>> List<Behaviour<?, T>> annotationBehaviour(Class<IOField<T, ?>> fieldType){
			return List.of(
				Behaviour.of(IOValue.class, BehaviourSupport::collectionLength),
				Behaviour.of(IONullability.class, BehaviourSupport::ioNullability),
				Behaviour.of(IOCompression.class, BehaviourSupport::packCompanion)
			);
		}
	}
	
	private final IOCompression.Type compression;
	private       IOField<T, byte[]> compressed;
	
	private IOFieldPrimitive.FInt<T> arraySize;
	
	public IOFieldByteArray(FieldAccessor<T> accessor){
		super(accessor);
		
		compression = accessor.getAnnotation(IOCompression.class).map(IOCompression::value).orElse(null);
		
		initSizeDescriptor(SizeDescriptor.Unknown.of((ioPool, prov, inst) -> {
			if(compression != null){
				return 0;
			}
			var siz = arraySize.getValue(ioPool, inst);
			if(siz>0) return siz;
			var arr = get(ioPool, inst);
			return arr == null? 0 : arr.length;
		}));
	}
	
	@Override
	public void init(FieldSet<T> fields){
		super.init(fields);
		arraySize = fields.requireExactInt(IOFieldTools.makeCollectionLenName(getAccessor()));
		if(compression != null){
			compressed = fields.requireExact(byte[].class, IOFieldTools.makePackName(getAccessor()));
		}
	}
	
	@Override
	public List<ValueGeneratorInfo<T, ?>> getGenerators(){
		if(compression == null){
			return super.getGenerators();
		}
		return Utils.concat(super.getGenerators(), new ValueGeneratorInfo<>(compressed, new ValueGenerator<T, byte[]>(){
			@Override
			public boolean shouldGenerate(VarPool<T> ioPool, DataProvider provider, T instance){
				return compressed.isNull(ioPool, instance);
			}
			@Override
			public byte[] generate(VarPool<T> ioPool, DataProvider provider, T instance, boolean allowExternalMod){
				byte[] raw = get(ioPool, instance);
				return raw == null? new byte[0] : compression.pack(raw);
			}
		}));
	}
	
	@Override
	public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		var arr = get(ioPool, instance);
		if(nullable() && arr == null){
			return;
		}
		if(compression != null){
			return;
		}
		dest.writeInts1(arr);
	}
	@Override
	public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		if(compression != null){
			var data = compression.unpack(compressed.get(ioPool, instance));
			set(ioPool, instance, data);
			return;
		}
		
		byte[] data;
		if(nullable() && getIsNull(ioPool, instance)) data = null;
		else{
			int size = arraySize.getValue(ioPool, instance);
			data = new byte[size];
			src.readFully(data);
		}
		set(ioPool, instance, data);
	}
	
	@Override
	public void skip(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		if(compression != null){
			return;
		}
		int size = arraySize.getValue(ioPool, instance);
		src.skipExact(size);
	}
}
