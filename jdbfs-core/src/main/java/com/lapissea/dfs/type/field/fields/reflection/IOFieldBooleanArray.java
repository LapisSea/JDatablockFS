package com.lapissea.dfs.type.field.fields.reflection;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.bit.BitInputStream;
import com.lapissea.dfs.io.bit.BitOutputStream;
import com.lapissea.dfs.io.bit.BitUtils;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.BehaviourSupport;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.type.field.fields.NullFlagCompanyField;

import java.io.IOException;
import java.util.List;
import java.util.Set;

public final class IOFieldBooleanArray<T extends IOInstance<T>> extends NullFlagCompanyField<T, boolean[]>{
	
	@SuppressWarnings("unused")
	private static final class Usage extends FieldUsage.InstanceOf<boolean[]>{
		public Usage(){ super(boolean[].class, Set.of(IOFieldBooleanArray.class)); }
		@Override
		public <T extends IOInstance<T>> IOField<T, boolean[]> create(FieldAccessor<T> field){
			return new IOFieldBooleanArray<>(field);
		}
		@Override
		public <T extends IOInstance<T>> List<Behaviour<?, T>> annotationBehaviour(Class<IOField<T, ?>> fieldType){
			return List.of(
				Behaviour.of(IOValue.class, BehaviourSupport::collectionLength),
				Behaviour.of(IONullability.class, BehaviourSupport::ioNullability)
			);
		}
	}
	
	private IOFieldPrimitive.FInt<T> arraySize;
	
	public IOFieldBooleanArray(FieldAccessor<T> accessor){
		super(accessor);
		
		initSizeDescriptor(SizeDescriptor.Unknown.of((ioPool, prov, inst) -> {
			var siz = arraySize.getValue(ioPool, inst);
			if(siz>0) return siz;
			var arr = get(ioPool, inst);
			return arr == null? 0 : BitUtils.bitsToBytes(arr.length);
		}));
	}
	@Override
	public void init(FieldSet<T> fields){
		super.init(fields);
		arraySize = fields.requireExactInt(IOFieldTools.makeCollectionLenName(getAccessor()));
	}
	
	@Override
	public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		var arr = get(ioPool, instance);
		if(arr == null) return;
		try(var b = new BitOutputStream(dest)){
			b.writeBits(arr);
		}
	}
	@Override
	public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		boolean[] data;
		if(getIsNull(ioPool, instance)) data = null;
		else{
			int size = arraySize.getValue(ioPool, instance);
			data = new boolean[size];
			try(var b = new BitInputStream(src, size)){
				b.readBits(data);
			}
		}
		set(ioPool, instance, data);
	}
	
	@Override
	public void skip(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		int size = arraySize.getValue(ioPool, instance);
		src.skipExact(size);
	}
}
