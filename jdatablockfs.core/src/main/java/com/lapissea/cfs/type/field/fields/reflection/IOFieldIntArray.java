package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.field.BehaviourSupport;
import com.lapissea.cfs.type.field.FieldSet;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.VirtualFieldDefinition;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.cfs.type.field.fields.NullFlagCompanyField;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static com.lapissea.cfs.type.field.StoragePool.IO;

public final class IOFieldIntArray<T extends IOInstance<T>> extends NullFlagCompanyField<T, int[]>{
	
	@SuppressWarnings("unused")
	private static final class Usage extends FieldUsage.InstanceOf<int[]>{
		public Usage(){ super(int[].class, Set.of(IOFieldIntArray.class)); }
		@Override
		public <T extends IOInstance<T>> IOField<T, int[]> create(FieldAccessor<T> field){
			return new IOFieldIntArray<>(field);
		}
		@Override
		public <T extends IOInstance<T>> List<Behaviour<?, T>> annotationBehaviour(Class<IOField<T, ?>> fieldType){
			return List.of(
				Behaviour.of(IOValue.class, BehaviourSupport::collectionLength),
				Behaviour.of(IONullability.class, BehaviourSupport::ioNullability),
				Behaviour.of(IOValue.class, (field, ann) -> {
					return new BehaviourRes<>(new VirtualFieldDefinition<T, NumberSize>(
						IO, IOFieldTools.makeNumberSizeName(field), NumberSize.class
					));
				})
			);
		}
	}
	
	private IOFieldPrimitive.FInt<T> arraySize;
	private IOField<T, NumberSize>   numSize;
	
	public IOFieldIntArray(FieldAccessor<T> accessor){
		super(accessor);
		
		initSizeDescriptor(SizeDescriptor.Unknown.of((ioPool, prov, inst) -> {
			var siz = getArrSize(ioPool, inst);
			if(siz == 0) return 0;
			var arr = get(ioPool, inst);
			if(arr == null) return 0;
			var nSiz = getNumSize(ioPool, inst);
			return arr.length*(long)nSiz.bytes;
		}));
	}
	
	private NumberSize getNumSize(VarPool<T> pool, T inst){
		return numSize.get(pool, inst);
	}
	
	private int getArrSize(VarPool<T> pool, T inst){
		return arraySize.getValue(pool, inst);
	}
	
	@Override
	public void init(FieldSet<T> fields){
		super.init(fields);
		arraySize = fields.requireExactInt(IOFieldTools.makeCollectionLenName(getAccessor()));
		numSize = fields.requireExact(NumberSize.class, IOFieldTools.makeNumberSizeName(getAccessor()));
	}
	
	@Override
	public List<ValueGeneratorInfo<T, ?>> getGenerators(){
		return Utils.concat(super.getGenerators(), new ValueGeneratorInfo<>(numSize, new ValueGenerator<>(){
			@Override
			public boolean shouldGenerate(VarPool<T> ioPool, DataProvider provider, T instance){
				if(numSize.isNull(ioPool, instance)){
					return true;
				}
				var arr       = get(ioPool, instance);
				var nSiz      = calcNumSize(arr);
				var actualSiz = getNumSize(ioPool, instance);
				return nSiz != actualSiz;
			}
			@Override
			public NumberSize generate(VarPool<T> ioPool, DataProvider provider, T instance, boolean allowExternalMod){
				var arr = get(ioPool, instance);
				return calcNumSize(arr);
			}
			
			private static NumberSize calcNumSize(int[] arr){
				if(arr == null) return NumberSize.VOID;
				int min = 0, max = 0;
				for(int i : arr){
					min = Math.min(min, i);
					max = Math.max(max, i);
				}
				var minSiz = NumberSize.bySizeSigned(min);
				var maxSiz = NumberSize.bySizeSigned(max);
				return minSiz.max(maxSiz);
			}
		}));
	}
	@Override
	public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		var arr = get(ioPool, instance);
		if(arr == null) return;
		var nSiz = getNumSize(ioPool, instance);
		for(int i : arr){
			nSiz.writeIntSigned(dest, i);
		}
	}
	@Override
	public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		int[] data;
		if(nullable() && getIsNull(ioPool, instance)) data = null;
		else{
			int size = getArrSize(ioPool, instance);
			var nSiz = getNumSize(ioPool, instance);
			data = new int[size];
			for(int i = 0; i<data.length; i++){
				data[i] = nSiz.readIntSigned(src);
			}
		}
		set(ioPool, instance, data);
	}
	
	@Override
	public void skip(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		int size = getArrSize(ioPool, instance);
		if(size == 0) return;
		var nSiz = getNumSize(ioPool, instance);
		src.skipExact(size*(long)nSiz.bytes);
	}
}
