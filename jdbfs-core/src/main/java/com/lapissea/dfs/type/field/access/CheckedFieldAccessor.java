package com.lapissea.dfs.type.field.access;

import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.iterableplus.Match;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.Objects;

public class CheckedFieldAccessor<CTyp extends IOInstance<CTyp>, T extends FieldAccessor<CTyp> & FieldAccessor.FieldOrMethod> implements FieldAccessor<CTyp>, FieldAccessor.FieldOrMethod{
	
	private final FieldAccessor<CTyp> test;
	private final FieldAccessor<CTyp> reference;
	
	public CheckedFieldAccessor(FieldAccessor<CTyp> test, FieldAccessor<CTyp> reference){
		this.test = Objects.requireNonNull(test);
		this.reference = Objects.requireNonNull(reference);
		getGenericType(null);
	}
	
	private static AssertionError fail(Object actual, Object expected){
		throw new AssertionError(
			"\n" +
			"  actual:   " + actual + "\n" +
			"  expected: " + expected
		);
	}
	
	@Override
	public int getTypeID(){
		var a = test.getTypeID();
		var b = reference.getTypeID();
		if(!Objects.equals(a, b)){
			throw fail(a, b);
		}
		return a;
	}
	@Override
	public boolean genericTypeHasArgs(){
		var a = test.genericTypeHasArgs();
		var b = reference.genericTypeHasArgs();
		if(!Objects.equals(a, b)){
			throw fail(a, b);
		}
		return a;
	}
	@Override
	public Struct<CTyp> getDeclaringStruct(){
		var a = test.getDeclaringStruct();
		var b = reference.getDeclaringStruct();
		if(!Objects.equals(a, b)){
			throw fail(a, b);
		}
		return a;
	}
	@Override
	public String getName(){
		var a = test.getName();
		var b = reference.getName();
		if(!Objects.equals(a, b)){
			throw fail(a, b);
		}
		return a;
	}
	@Override
	public Object get(VarPool<CTyp> ioPool, CTyp instance){
		var a = test.get(ioPool, instance);
		var b = reference.get(ioPool, instance);
		if(!Objects.equals(a, b)){
			throw fail(a, b);
		}
		return a;
	}
	@Override
	public void set(VarPool<CTyp> ioPool, CTyp instance, Object value){
		test.set(ioPool, instance, value);
		reference.set(ioPool, instance, value);
		
		var a = test.get(ioPool, instance);
		var b = reference.get(ioPool, instance);
		if(!Objects.equals(a, b)){
			throw fail(a, b);
		}
	}
	@Override
	public double getDouble(VarPool<CTyp> ioPool, CTyp instance){
		var a = test.getDouble(ioPool, instance);
		var b = reference.getDouble(ioPool, instance);
		if(!Objects.equals(a, b)){
			throw fail(a, b);
		}
		return a;
	}
	@Override
	public void setDouble(VarPool<CTyp> ioPool, CTyp instance, double value){
		test.setDouble(ioPool, instance, value);
		reference.setDouble(ioPool, instance, value);
		var a = test.getDouble(ioPool, instance);
		var b = reference.getDouble(ioPool, instance);
		if(!Objects.equals(a, b)){
			throw fail(a, b);
		}
	}
	@Override
	public float getFloat(VarPool<CTyp> ioPool, CTyp instance){
		var a = test.getFloat(ioPool, instance);
		var b = reference.getFloat(ioPool, instance);
		if(!Objects.equals(a, b)){
			throw fail(a, b);
		}
		return a;
	}
	@Override
	public void setFloat(VarPool<CTyp> ioPool, CTyp instance, float value){
		test.setFloat(ioPool, instance, value);
		reference.setFloat(ioPool, instance, value);
		var a = test.getFloat(ioPool, instance);
		var b = reference.getFloat(ioPool, instance);
		if(!Objects.equals(a, b)){
			throw fail(a, b);
		}
	}
	@Override
	public byte getByte(VarPool<CTyp> ioPool, CTyp instance){
		var a = test.getByte(ioPool, instance);
		var b = reference.getByte(ioPool, instance);
		if(!Objects.equals(a, b)){
			throw fail(a, b);
		}
		return a;
	}
	@Override
	public void setByte(VarPool<CTyp> ioPool, CTyp instance, byte value){
		test.setByte(ioPool, instance, value);
		reference.setByte(ioPool, instance, value);
		var a = test.getByte(ioPool, instance);
		var b = reference.getByte(ioPool, instance);
		if(!Objects.equals(a, b)){
			throw fail(a, b);
		}
	}
	@Override
	public boolean getBoolean(VarPool<CTyp> ioPool, CTyp instance){
		var a = test.getBoolean(ioPool, instance);
		var b = reference.getBoolean(ioPool, instance);
		if(!Objects.equals(a, b)){
			throw fail(a, b);
		}
		return a;
	}
	@Override
	public void setBoolean(VarPool<CTyp> ioPool, CTyp instance, boolean value){
		test.setBoolean(ioPool, instance, value);
		reference.setBoolean(ioPool, instance, value);
		var a = test.getBoolean(ioPool, instance);
		var b = reference.getBoolean(ioPool, instance);
		if(!Objects.equals(a, b)){
			throw fail(a, b);
		}
	}
	@Override
	public long getLong(VarPool<CTyp> ioPool, CTyp instance){
		var a = test.getLong(ioPool, instance);
		var b = reference.getLong(ioPool, instance);
		if(!Objects.equals(a, b)){
			throw fail(a, b);
		}
		return a;
	}
	@Override
	public void setLong(VarPool<CTyp> ioPool, CTyp instance, long value){
		test.setLong(ioPool, instance, value);
		reference.setLong(ioPool, instance, value);
		var a = test.getLong(ioPool, instance);
		var b = reference.getLong(ioPool, instance);
		if(!Objects.equals(a, b)){
			throw fail(a, b);
		}
	}
	@Override
	public int getInt(VarPool<CTyp> ioPool, CTyp instance){
		var a = test.getInt(ioPool, instance);
		var b = reference.getInt(ioPool, instance);
		if(!Objects.equals(a, b)){
			throw fail(a, b);
		}
		return a;
	}
	@Override
	public void setInt(VarPool<CTyp> ioPool, CTyp instance, int value){
		test.setInt(ioPool, instance, value);
		reference.setInt(ioPool, instance, value);
		var a = test.getInt(ioPool, instance);
		var b = reference.getInt(ioPool, instance);
		if(!Objects.equals(a, b)){
			throw fail(a, b);
		}
	}
	@Override
	public short getShort(VarPool<CTyp> ioPool, CTyp instance){
		var a = test.getShort(ioPool, instance);
		var b = reference.getShort(ioPool, instance);
		if(!Objects.equals(a, b)){
			throw fail(a, b);
		}
		return a;
	}
	@Override
	public void setShort(VarPool<CTyp> ioPool, CTyp instance, short value){
		test.setShort(ioPool, instance, value);
		reference.setShort(ioPool, instance, value);
		var a = test.getShort(ioPool, instance);
		var b = reference.getShort(ioPool, instance);
		if(!Objects.equals(a, b)){
			throw fail(a, b);
		}
	}
	@Override
	public char getChar(VarPool<CTyp> ioPool, CTyp instance){
		var a = test.getChar(ioPool, instance);
		var b = reference.getChar(ioPool, instance);
		if(!Objects.equals(a, b)){
			throw fail(a, b);
		}
		return a;
	}
	@Override
	public void setChar(VarPool<CTyp> ioPool, CTyp instance, char value){
		test.setChar(ioPool, instance, value);
		reference.setChar(ioPool, instance, value);
		var a = test.getChar(ioPool, instance);
		var b = reference.getChar(ioPool, instance);
		if(!Objects.equals(a, b)){
			throw fail(a, b);
		}
	}
	@Override
	public int compareTo(FieldAccessor<CTyp> o){
		var a = test.compareTo(o);
		var b = reference.compareTo(o);
		if(!Objects.equals(a, b)){
			throw fail(a, b);
		}
		return a;
	}
	@Override
	public boolean canBeNull(){
		var a = test.canBeNull();
		var b = reference.canBeNull();
		if(!Objects.equals(a, b)){
			throw fail(a, b);
		}
		return a;
	}
	@Override
	public boolean isReadOnly(){
		var a = test.isReadOnly();
		var b = reference.isReadOnly();
		if(!Objects.equals(a, b)){
			throw fail(a, b);
		}
		return a;
	}
	@Override
	public <T extends Annotation> Match<T> getAnnotation(Class<T> annotationClass){
		var a = test.getAnnotation(annotationClass);
		var b = reference.getAnnotation(annotationClass);
		if(!Objects.equals(a, b)){
			throw fail(a, b);
		}
		return a;
	}
	@Override
	public boolean hasAnnotation(Class<? extends Annotation> annotationClass){
		var a = test.hasAnnotation(annotationClass);
		var b = reference.hasAnnotation(annotationClass);
		if(!Objects.equals(a, b)){
			throw fail(a, b);
		}
		return a;
	}
	@Override
	public Map<Class<? extends Annotation>, ? extends Annotation> getAnnotations(){
		var a = test.getAnnotations();
		var b = reference.getAnnotations();
		if(!Objects.equals(a, b)){
			throw fail(a, b);
		}
		return a;
	}
	@Override
	public Type getGenericType(GenericContext genericContext){
		var a = test.getGenericType(genericContext);
		var b = reference.getGenericType(genericContext);
		if(!Objects.equals(a, b)){
			throw fail(a, b);
		}
		return a;
	}
	@Override
	public Class<?> getType(){
		var a = test.getType();
		var b = reference.getType();
		if(!Objects.equals(a, b)){
			throw fail(a, b);
		}
		return a;
	}
	@Override
	public AccessType getter(){
		var a = ((FieldAccessor.FieldOrMethod)test).getter();
		var b = ((FieldAccessor.FieldOrMethod)reference).getter();
		if(!Objects.equals(a, b)){
			throw fail(a, b);
		}
		return a;
	}
	@Override
	public AccessType setter(){
		var a = ((FieldAccessor.FieldOrMethod)test).setter();
		var b = ((FieldAccessor.FieldOrMethod)reference).setter();
		if(!Objects.equals(a, b)){
			throw fail(a, b);
		}
		return a;
	}
}
