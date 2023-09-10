package com.lapissea.jorth.lang;

import com.lapissea.jorth.MalformedJorth;
import com.lapissea.jorth.lang.type.BaseType;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.TypeSource;

public final class ClassName{
	
	public static ClassName of(Class<?> t){
		return dotted(t.getName());
	}
	
	public static ClassName dotted(String s){
		if(s.indexOf('/') != -1) throw new IllegalArgumentException(s);
		return new ClassName(s, null);
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
	
	public boolean instanceOf(TypeSource source, ClassName right) throws MalformedJorth{
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
		if(superType != null && superType.name().instanceOf(source, right)) return true;
		var interfaces = info.interfaces();
		for(GenericType interf : interfaces){
			if(interf.raw().instanceOf(source, right)){
				return true;
			}
		}
		return false;
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
	
	public BaseType baseType(){
		return BaseType.of(this);
	}
}
