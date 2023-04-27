package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.objects.text.AutoText;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.FieldSet;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

public class IOFieldStringList<T extends IOInstance<T>> extends IOField<T, List<String>>{
	
	private IOFieldPrimitive.FInt<T> arraySize;
	
	public IOFieldStringList(FieldAccessor<T> accessor){
		super(accessor);
		initSizeDescriptor(SizeDescriptor.Unknown.of(0, OptionalLong.empty(), (ioPool, prov, inst) -> {
			var arr = get(ioPool, inst);
			if(arr == null) return 0;
			return arr.stream().map(AutoText::new).mapToLong(t -> AutoText.PIPE.calcUnknownSize(prov, t, WordSpace.BYTE)).sum();
		}));
	}
	@Override
	public void init(FieldSet<T> fields){
		super.init(fields);
		arraySize = fields.requireExactInt(IOFieldTools.makeCollectionLenName(getAccessor()));
	}
	
	@Override
	public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		var arr  = get(ioPool, instance);
		var text = new AutoText();
		for(String s : arr){
			text.setData(s);
			AutoText.PIPE.write(provider, dest, text);
		}
	}
	@Override
	public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		int size = arraySize.getValue(ioPool, instance);
		
		var arr = new ArrayList<String>(size);
		
		for(int i = 0; i<size; i++){
			arr.add(i, AutoText.PIPE.readNew(provider, src, null).getData());
		}
		
		set(ioPool, instance, arr);
	}
	
	@Override
	public void skip(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		int size = arraySize.getValue(ioPool, instance);
		for(int i = 0; i<size; i++){
			AutoText.PIPE.skip(provider, src, null);
		}
	}
}
