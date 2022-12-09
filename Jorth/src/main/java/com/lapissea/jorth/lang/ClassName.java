package com.lapissea.jorth.lang;

import com.lapissea.jorth.MalformedJorthException;
import com.lapissea.jorth.lang.type.TypeSource;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ClassName{
	
	private static final Map<String, ClassName> CACHE = new ConcurrentHashMap<>();
	
	public static ClassName of(Class<?> t){
		return dotted(t.getName());
	}
	
	public static ClassName dotted(String s){
		return CACHE.computeIfAbsent(s, st -> {
			if(s.indexOf('/') != -1) throw new IllegalArgumentException(s);
			return new ClassName(s, null);
		});
	}
	
	public static ClassName slashed(String s){
		if(s.indexOf('.') != -1) throw new IllegalArgumentException(s);
		return new ClassName(null, s);
	}
	
	private String dotted;
	private String slashed;
	
	private ClassName(String dotted, String slashed){
		this.dotted = dotted;
		this.slashed = slashed;
	}
	
	public String dotted(){
		if(dotted == null) dotted = slashed.replace('/', '.');
		return dotted;
	}
	public String slashed(){
		if(slashed == null) slashed = dotted.replace('.', '/');
		return slashed;
	}
	
	public String any(){
		return dotted != null? dotted : slashed;
	}
	
	public boolean instanceOf(TypeSource source, ClassName right) throws MalformedJorthException{
		if(this.equals(right)){
			return true;
		}
		if(right.dotted().equals(Object.class.getName())){
			return true;
		}
		if(this.dotted().equals(Object.class.getName())){
			return false;
		}
		
		var info      = source.byName(this);
		var superType = info.superType();
		if(superType == null) return false;
		return superType.name().instanceOf(source, right);
	}
	
	@Override
	public String toString(){
		return dotted();
	}
	
	@Override
	public boolean equals(Object obj){
		if(obj == null) return false;
		if(obj == this) return true;
		return obj instanceof ClassName n && equals(n);
	}
	public boolean equals(ClassName that){
		if(that == null) return false;
		if(that == this) return true;
		if(that.dotted != null && this.dotted != null){
			return that.dotted.equals(this.dotted);
		}
		if(that.slashed != null && this.slashed != null){
			return that.slashed.equals(this.slashed);
		}
		return that.slashed().equals(this.slashed());
	}
	@Override
	public int hashCode(){
		return slashed().hashCode();
	}
}
