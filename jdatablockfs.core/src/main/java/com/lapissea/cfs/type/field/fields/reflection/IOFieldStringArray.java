package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.objects.text.AutoText;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;

import java.io.IOException;
import java.util.Arrays;
import java.util.OptionalLong;

public class IOFieldStringArray<T extends IOInstance<T>> extends IOField<T, String[]>{
	
	private final SizeDescriptor<T>        descriptor;
	private       IOFieldPrimitive.FInt<T> arraySize;
	
	
	public IOFieldStringArray(FieldAccessor<T> accessor){
		super(accessor);
		
		descriptor=SizeDescriptor.Unknown.of(0, OptionalLong.empty(), (ioPool, prov, inst)->{
			var arr=get(ioPool, inst);
			if(arr==null) return 0;
			return Arrays.stream(arr).map(AutoText::new).mapToLong(t->AutoText.PIPE.calcUnknownSize(prov, t, WordSpace.BYTE)).sum();
		});
	}
	@Override
	public void init(){
		super.init();
		arraySize=declaringStruct().getFields().requireExactInt(IOFieldTools.makeCollectionLenName(getAccessor()));
	}
	
	@Override
	public SizeDescriptor<T> getSizeDescriptor(){
		return descriptor;
	}
	@Override
	public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		var arr =get(ioPool, instance);
		var text=new AutoText();
		for(String s : arr){
			text.setData(s);
			AutoText.PIPE.write(provider, dest, text);
		}
	}
	@Override
	public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		int size=arraySize.getValue(ioPool, instance);
		
		var arr=new String[size];
		
		var text=new AutoText();
		for(int i=0;i<arr.length;i++){
			AutoText.PIPE.read(provider, src, text, null);
			arr[i]=text.getData();
		}
		
		set(ioPool, instance, arr);
	}
	
	@Override
	public void skipRead(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		readReported(ioPool, provider, src, instance, genericContext);
	}
}
