package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.type.*;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.cfs.type.field.VirtualFieldDefinition.StoragePool.IO;

public class IOFieldInstanceList<T extends IOInstance<T>, ValType extends IOInstance<ValType>> extends IOField<T, List<ValType>>{
	
	private final SizeDescriptor<T>   descriptor;
	private final Class<ValType>      component;
	private       IOField<T, Integer> listSize;
	
	private StructPipe<ValType> valPipe;
	
	public IOFieldInstanceList(FieldAccessor<T> accessor){
		super(accessor);
		var type=(ParameterizedType)accessor.getGenericType(null);
		
		component=(Class<ValType>)Utils.typeToRaw(type.getActualTypeArguments()[0]);
		
		descriptor=SizeDescriptor.Unknown.of(WordSpace.BYTE, 0, OptionalLong.empty(), (ioPool, prov, inst)->{
			var arr=get(null, inst);
			if(arr.size()==0) return 0;
			
			var desc=getValPipe().getSizeDescriptor();
			if(desc.hasFixed()){
				return arr.size()*desc.requireFixed(WordSpace.BYTE);
			}
			return arr.stream().mapToLong(instance->desc.calcUnknown(instance.getThisStruct().allocVirtualVarPool(IO), prov, instance, WordSpace.BYTE)).sum();
		});
	}
	
	@Override
	public List<ValType> get(Struct.Pool<T> ioPool, T instance){
		return getNullable(ioPool, instance);
	}
	
	private StructPipe<ValType> getValPipe(){
		if(valPipe==null){
			var p=ContiguousStructPipe.of(component);
			p.waitForState(StagedInit.STATE_DONE);
			valPipe=p;
		}
		return valPipe;
	}
	
	@Override
	public void init(){
		super.init();
		listSize=declaringStruct().getFields().requireExact(int.class, IOFieldTools.makeArrayLenName(getAccessor()));
	}
	
	private int getListSize(Struct.Pool<T> ioPool, T inst){
		return listSize.get(ioPool, inst);
	}
	
	@Override
	public SizeDescriptor<T> getSizeDescriptor(){
		return descriptor;
	}
	@Override
	public void write(Struct.Pool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		var pip=getValPipe();
		
		var arr=get(ioPool, instance);
		if(DEBUG_VALIDATION){
			int size=getListSize(ioPool, instance);
			assert arr.size()==size:arr.size()+" "+size;
		}
		for(ValType el : arr){
			if(DEBUG_VALIDATION){
				var siz=pip.calcUnknownSize(provider, el, WordSpace.BYTE);
				
				try(var buff=dest.writeTicket(siz).requireExact().submit()){
					pip.write(provider, buff, el);
				}
			}else{
				pip.write(provider, dest, el);
			}
		}
	}
	
	@Override
	public void read(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		var pip=getValPipe();
		
		
		int size=getListSize(ioPool, instance);
		
		List<ValType> data=new ArrayList<>(size);
		for(int i=0;i<size;i++){
			data.add(pip.readNew(provider, src, genericContext));
		}
		set(ioPool, instance, data);
	}
	
	@Override
	public void skipRead(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		var pip=getValPipe();
		
		int size            =getListSize(ioPool, instance);
		var fixedElementSize=pip.getSizeDescriptor().getFixed(WordSpace.BYTE);
		if(fixedElementSize.isPresent()){
			src.skipExact(size*fixedElementSize.getAsLong());
			return;
		}
		ValType inst=pip.getType().emptyConstructor().get();
		for(int i=0;i<size;i++){
			pip.read(provider, src, inst, genericContext);
		}
	}
}
