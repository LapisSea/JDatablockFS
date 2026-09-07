package com.lapissea.jorth;

import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.type.GenericType;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;

import java.lang.reflect.Array;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class AnnotationDefinition{
	
	public final  ClassName           type;
	private final Map<String, Object> args = new LinkedHashMap<>();
	
	public AnnotationDefinition(ClassName type){
		this.type = type;
	}
	
	public AnnotationDefinition arg(String name, Object value){
		if(args.put(Objects.requireNonNull(name), value) != null){
			throw new IllegalArgumentException("Duplicate argument " + name);
		}
		return this;
	}
	
	public void visit(MethodVisitor visitor){
		visit(visitor.visitAnnotation(jType(), true));
	}
	public void visit(ClassVisitor visitor){
		visit(visitor.visitAnnotation(jType(), true));
	}
	public void visit(FieldVisitor visitor){
		visit(visitor.visitAnnotation(jType(), true));
	}
	private String jType(){
		return new GenericType(type).jvmDescriptorStr();
	}
	private void visit(AnnotationVisitor annWriter){
		for(var e : args.entrySet()){
			visitValue(annWriter, e.getKey(), e.getValue());
		}
		annWriter.visitEnd();
	}
	private static void visitValue(AnnotationVisitor writer, String name, Object value){
		if(value.getClass().isArray()){
			var arrayWriter = writer.visitArray(name);
			for(int i = 0; i<Array.getLength(value); i++){
				visitValue(arrayWriter, null, Array.get(value, i));
			}
			arrayWriter.visitEnd();
		}else if(value instanceof Enum<?> enumValue){
			writer.visitEnum(name, GenericType.of(enumValue.getDeclaringClass()).jvmDescriptorStr(), enumValue.name());
		}else{
			writer.visit(name, value);
		}
	}
}
