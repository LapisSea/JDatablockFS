package com.lapissea.cfs.io.struct.engine;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.IOStruct.Get.Getter;
import com.lapissea.cfs.io.struct.StructImpl;
import com.lapissea.cfs.io.struct.ValueRelations;
import com.lapissea.cfs.io.struct.VariableNode;
import com.lapissea.cfs.io.struct.engine.impl.*;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NotImplementedException;

import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;

import static com.lapissea.cfs.io.struct.IOStruct.*;
import static com.lapissea.cfs.io.struct.IOStruct.Set.*;

public class PrimitiveArrayNodeMaker extends StructReflectionImpl.NodeMaker{
	@SuppressWarnings("unchecked")
	@Override
	protected VariableNode<?> makeNode(IOStruct clazz, String name, ValueRelations.ValueInfo info){
		PrimitiveArrayValue valueAnn;
		Field               valueField;
		VarHandle           varH;
		{
			StructImpl.Annotated<Field, ?> val=info.value();
			
			valueAnn=(PrimitiveArrayValue)val.annotation();
			
			valueField=val.val();
			valueField.setAccessible(true);
			varH=Utils.makeVarHandle(valueField);
		}
		
		var valueType =valueField.getType();
		var fixedCount=valueAnn.fixedElements();
		
		VariableNode.VarInfo vInfo=new VariableNode.VarInfo(name,valueAnn.index());
		
		if(valueType==boolean[].class){
			if(valueAnn.fixedElements()==-1){
				return new ArrayBoolIOImpl(vInfo, varH, Getter.get(info, valueType), Setter.get(info, valueType));
			}else{
				return new ArrayBoolFixedIOImpl(vInfo, fixedCount, varH, Getter.get(info, valueType), Setter.get(info, valueType));
			}
		}
		
		NumberSize defaultSize=valueAnn.defaultSize();
		
		VarHandle varSize;
		if(valueAnn.sizeRef().isEmpty()) varSize=null;
		else{
			try{
				Field fSize=clazz.instanceClass.getDeclaredField(valueAnn.sizeRef());
				if(fSize.getType()!=NumberSize.class) throw new MalformedStructLayout(fSize+" must be a NumberSize enum!");
				varSize=Utils.makeVarHandle(fSize);
			}catch(ReflectiveOperationException e){ throw new MalformedStructLayout(e); }
		}
		
		Getter<?> get=Getter.get(info, valueType);
		Setter<?> set=Setter.get(info, valueType);
		
		if(valueType==long[].class){
			if(defaultSize==NumberSize.VOID) defaultSize=NumberSize.LONG;
			if(varSize!=null) return new LongArrayVarSizeIOImpl(vInfo, fixedCount, varH, (Getter<long[]>)get, (Setter<long[]>)set, varSize, defaultSize);
			if(fixedCount==-1) return new LongArrayIOImpl(vInfo, varH, defaultSize, (Getter<long[]>)get, (Setter<long[]>)set);
			else return new LongArrayFixedIOImpl(vInfo, fixedCount, varH, defaultSize, (Getter<long[]>)get, (Setter<long[]>)set);
		}
		if(valueType==int[].class){
			if(defaultSize==NumberSize.VOID) defaultSize=NumberSize.INT;
			if(varSize!=null) return new IntArrayVarSizeIOImpl(vInfo, fixedCount, varH, (Getter<int[]>)get, (Setter<int[]>)set, varSize, defaultSize);
			if(fixedCount==-1) return new IntArrayIOImpl(vInfo, varH, defaultSize, (Getter<int[]>)get, (Setter<int[]>)set);
			else return new IntArrayFixedIOImpl(vInfo, fixedCount, varH, defaultSize, (Getter<int[]>)get, (Setter<int[]>)set);
		}
		if(valueType==float[].class){
			if(defaultSize==NumberSize.VOID) defaultSize=NumberSize.INT;
			if(varSize!=null) return new FloatArrayVarSizeIOImpl(vInfo, fixedCount, varH, (Getter<float[]>)get, (Setter<float[]>)set, varSize, defaultSize);
			if(fixedCount==-1) return new FloatArrayIOImpl(vInfo, varH, defaultSize, (Getter<float[]>)get, (Setter<float[]>)set);
			else return new FloatArrayFixedIOImpl(vInfo, fixedCount, varH, defaultSize, (Getter<float[]>)get, (Setter<float[]>)set);
		}
		if(valueType==double[].class){
			if(defaultSize==NumberSize.VOID) defaultSize=NumberSize.LONG;
			if(varSize!=null) return new DoubleArrayVarSizeIOImpl(vInfo, fixedCount, varH, (Getter<double[]>)get, (Setter<double[]>)set, varSize, defaultSize);
			if(fixedCount==-1) return new DoubleArrayIOImpl(vInfo, varH, defaultSize, (Getter<double[]>)get, (Setter<double[]>)set);
			else return new DoubleArrayFixedIOImpl(vInfo, fixedCount, varH, defaultSize, (Getter<double[]>)get, (Setter<double[]>)set);
		}
		if(valueType==byte[].class){
			if(defaultSize==NumberSize.VOID) defaultSize=NumberSize.BYTE;
			if(defaultSize!=NumberSize.BYTE) throw new MalformedStructLayout("byte[] must have BYTE element size");
			if(varSize!=null) LogUtil.printlnEr("Warning: ignored byte[] variable size value, byte[] element is always BYTE sized");
			if(fixedCount==-1) return new ByteArrayIOImpl(vInfo, varH, (Getter<byte[]>)get, (Setter<byte[]>)set);
			else return new ByteArrayFixedIOImpl(vInfo, fixedCount, varH, (Getter<byte[]>)get, (Setter<byte[]>)set);
		}
		
		
		throw new NotImplementedException(valueType.toString());
	}
}
