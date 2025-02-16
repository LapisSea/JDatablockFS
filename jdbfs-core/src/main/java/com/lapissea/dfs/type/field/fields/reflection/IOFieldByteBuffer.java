package com.lapissea.dfs.type.field.fields.reflection;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.BehaviourSupport;
import com.lapissea.dfs.type.field.FieldNames;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.type.field.StoragePool;
import com.lapissea.dfs.type.field.VirtualFieldDefinition;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.type.field.annotations.IOCompression;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.type.field.fields.NullFlagCompanyField;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class IOFieldByteBuffer<T extends IOInstance<T>> extends NullFlagCompanyField<T, ByteBuffer>{
	
	@SuppressWarnings("unused")
	private static final class Usage extends FieldUsage.InstanceOf<ByteBuffer>{
		public Usage(){ super(ByteBuffer.class, Set.of(IOFieldByteBuffer.class)); }
		@Override
		public <T extends IOInstance<T>> IOField<T, ByteBuffer> create(FieldAccessor<T> field){
			return new IOFieldByteBuffer<>(field);
		}
		
		@Override
		public <T extends IOInstance<T>> List<Behaviour<?, T>> annotationBehaviour(Class<IOField<T, ?>> fieldType){
			return List.of(
				Behaviour.of(IOValue.class, BehaviourSupport::collectionLength),
				Behaviour.of(IOValue.class, field -> {
					return new BehaviourRes<T>(new VirtualFieldDefinition<>(
						StoragePool.IO,
						FieldNames.modifiable(field),
						boolean.class
					));
				}),
				Behaviour.of(IONullability.class, BehaviourSupport::ioNullability),
				Behaviour.of(IOCompression.class, BehaviourSupport::packCompanion)
			);
		}
	}
	
	private final IOCompression.Type compression;
	private       IOField<T, byte[]> compressed;
	
	private IOFieldPrimitive.FInt<T>     arraySize;
	private IOFieldPrimitive.FBoolean<T> modifiable;
	
	public IOFieldByteBuffer(FieldAccessor<T> accessor){
		super(accessor);
		
		compression = accessor.getAnnotation(IOCompression.class).map(IOCompression::value).orElse(null);
		
		initSizeDescriptor(SizeDescriptor.Unknown.of((ioPool, prov, inst) -> {
			if(compression != null){
				return 0;
			}
			var siz = arraySize.getValue(ioPool, inst);
			if(siz>0) return siz;
			var arr = get(ioPool, inst);
			return arr == null? 0 : arr.capacity();
		}));
	}
	@Override
	protected Set<TypeFlag> computeTypeFlags(){
		return Set.of(TypeFlag.HAS_NO_POINTERS);
	}
	
	@Override
	public void init(FieldSet<T> fields){
		super.init(fields);
		arraySize = fields.requireExactInt(FieldNames.collectionLen(getAccessor()));
		modifiable = fields.requireExactBoolean(FieldNames.modifiable(getAccessor()));
		if(compression != null){
			compressed = fields.requireExact(byte[].class, FieldNames.pack(getAccessor()));
		}
	}
	
	@Override
	public ByteBuffer get(VarPool<T> ioPool, T instance){
		return getNullable(ioPool, instance, () -> ByteBuffer.allocate(0));
	}
	
	@Override
	public List<ValueGeneratorInfo<T, ?>> getGenerators(){
		var gen = new ArrayList<>(super.getGenerators());
		if(compression != null){
			gen.add(new ValueGeneratorInfo<>(compressed, new ValueGenerator<T, byte[]>(){
				@Override
				public boolean shouldGenerate(VarPool<T> ioPool, DataProvider provider, T instance){
					return compressed.isNull(ioPool, instance);
				}
				@Override
				public byte[] generate(VarPool<T> ioPool, DataProvider provider, T instance, boolean allowExternalMod){
					ByteBuffer raw = get(ioPool, instance);
					if(raw == null) return null;
					byte[] bytes;
					if(raw.hasArray() && raw.arrayOffset() == 0 && raw.capacity() == raw.array().length){
						bytes = raw.array();
					}else{
						bytes = new byte[raw.capacity()];
						raw.get(0, bytes);
					}
					return compression.pack(bytes);
				}
			}));
		}
		gen.add(new ValueGeneratorInfo<>(modifiable, new ValueGenerator.NoCheck<T, Boolean>(){
			@Override
			public Boolean generate(VarPool<T> ioPool, DataProvider provider, T instance, boolean allowExternalMod){
				var raw = get(ioPool, instance);
				return raw != null && !raw.isReadOnly();
			}
		}));
		return gen;
	}
	
	@Override
	public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		var buff = get(ioPool, instance);
		if(nullable() && buff == null){
			return;
		}
		if(compression != null){
			return;
		}
		dest.write(buff, 0, buff.capacity());
	}
	@Override
	public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		var modifiable = this.modifiable.getValue(ioPool, instance);
		if(compression != null){
			var data = compression.unpack(compressed.get(ioPool, instance));
			var bb   = ByteBuffer.wrap(data);
			set(ioPool, instance, modifiable? bb : bb.asReadOnlyBuffer());
			return;
		}
		
		ByteBuffer bb;
		if(nullable() && getIsNull(ioPool, instance)) bb = null;
		else{
			int size = arraySize.getValue(ioPool, instance);
			var data = new byte[size];
			src.readFully(data);
			bb = ByteBuffer.wrap(data);
			if(!modifiable) bb = bb.asReadOnlyBuffer();
		}
		set(ioPool, instance, bb);
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
