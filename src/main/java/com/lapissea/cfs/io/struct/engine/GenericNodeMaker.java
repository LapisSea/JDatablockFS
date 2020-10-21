package com.lapissea.cfs.io.struct.engine;

import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.io.ReaderWriter;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.IOStruct.Get.Getter;
import com.lapissea.cfs.io.struct.IOStruct.Read.Reader;
import com.lapissea.cfs.io.struct.IOStruct.Set.Setter;
import com.lapissea.cfs.io.struct.IOStruct.Size.Sizer;
import com.lapissea.cfs.io.struct.IOStruct.Write.Writer;
import com.lapissea.cfs.io.struct.StructImpl;
import com.lapissea.cfs.io.struct.ValueRelations;
import com.lapissea.cfs.io.struct.VariableNode;
import com.lapissea.cfs.io.struct.engine.impl.FixedGenericIOImpl;
import com.lapissea.cfs.io.struct.engine.impl.GenericIOImpl;
import com.lapissea.util.TextUtil;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Function;

public class GenericNodeMaker<T> extends StructReflectionImpl.NodeMaker<T>{
	
	
	@Override
	@SuppressWarnings("unchecked")
	protected VariableNode<T> makeNode(IOStruct clazz, String name, ValueRelations.ValueInfo info){
		
		IOStruct.Value valueAnn;
		Field          valueField;
		{
			StructImpl.Annotated<Field, ?> val=info.value();
			
			valueAnn=(IOStruct.Value)val.annotation();
			
			valueField=val.val();
			valueField.setAccessible(true);
		}
		
		var valueType=valueField.getGenericType();
		
		
		Getter<T> getFun     =Getter.get(info, valueType);
		Setter<T> setFun     =Setter.get(info, valueType);
		Reader<T> readFunTmp =Reader.get(info, valueType), readFun;
		Writer<T> writeFunTmp=Writer.get(info, valueType), writeFun;
		Sizer<T>  sizerFunTmp=Sizer.get(info, valueType), sizerFun;
		
		OptionalInt  fixedSize=OptionalInt.empty();
		OptionalLong maxSize  =OptionalLong.empty();
		
		if(sizerFunTmp!=null){
			IOStruct.Size siz=(IOStruct.Size)info.functions().get(IOStruct.Size.class).annotation();
			if(siz.fixedSize()!=-1){
				fixedSize=OptionalInt.of(Math.toIntExact(siz.fixedSize()));
				maxSize=OptionalLong.of(siz.fixedSize());
			}
		}
		
		ReaderWriter<T> rw=ReaderWriter.getInstance(clazz.instanceClass, valueAnn.rw(), valueAnn.rwArgs());
		if(rw!=null){
			fixedSize=rw.getFixedSize();
			maxSize=rw.getMaxSize().stream().asLongStream().findAny();
			
			if(readFunTmp==null) readFunTmp=rw::read;
			if(writeFunTmp==null) writeFunTmp=rw::write;
			if(sizerFunTmp==null) sizerFunTmp=rw::mapSize;
		}
		
		readFun=readFunTmp;
		writeFun=writeFunTmp;
		sizerFun=sizerFunTmp;
		
		
		Function<Field, String> toStr=f->f.getDeclaringClass().getName()+"."+f.getName();
		
		var errors=new ArrayList<String>(3);
		if(readFun==null) errors.add("reader");
		if(writeFun==null) errors.add("reader");
		if(sizerFun==null) errors.add("size mapping");
		
		if(!errors.isEmpty()){
			throw new MalformedStructLayout(toStr.apply(valueField)+" needs a "+ReaderWriter.class.getSimpleName()+" definition or "+String.join(", ", errors)+TextUtil.plural(" function", errors.size()));
		}
		
		if(fixedSize.isPresent()){
			return new FixedGenericIOImpl<>(name, valueAnn.index(), fixedSize.getAsInt(), valueField, getFun, setFun, sizerFun, readFun, writeFun);
		}
		
		return new GenericIOImpl<>(name, valueAnn.index(), valueField, getFun, setFun, sizerFun, readFun, writeFun, maxSize);
	}
}
