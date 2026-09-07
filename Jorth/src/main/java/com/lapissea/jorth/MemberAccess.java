package com.lapissea.jorth;

import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.TypeSource;
import com.lapissea.jorth.lang.type.Visibility;

/**
 * Checks member references emitted by Jorth. Package access assumes the generated
 * class is defined in the same runtime package (including loader) as its peers.
 * Hidden nestmate definitions explicitly supply their definition host; ordinary
 * ClassLoader-based definitions receive no nestmate privileges.
 */
final class MemberAccess{
	private MemberAccess(){ }
	
	static void check(TypeSource source, ClassName caller, ClassName owner, String member,
	                  Visibility visibility, GenericType receiver, boolean constructor) throws MalformedJorth{
		if(caller.equals(owner)) return;
		boolean samePackage = packageName(caller).equals(packageName(owner));
		if(!source.byName(owner).isPublic() && !samePackage){
			throw new MalformedJorth("Cannot access class " + owner + " for member " + member + " from " + caller);
		}
		// The JVM exempts array clone from the protected receiver restriction.
		boolean arrayClone = receiver != null && receiver.dims()>0 &&
		                     owner.equals(ClassName.of(Object.class)) && member.equals("clone");
		boolean allowed = switch(visibility){
			case PUBLIC -> true;
			case PRIVATE -> source.areNestmates(caller, owner);
			case PACKAGE_PRIVATE -> samePackage;
			case PROTECTED -> samePackage ||
			                  (caller.instanceOf(source, owner) &&
			                   (constructor || arrayClone || receiver == null || receiver.instanceOf(source, new GenericType(caller))));
		};
		if(!allowed){
			throw new MalformedJorth("Cannot access " + visibility + " member " + owner + "#" + member + " from " + caller);
		}
	}
	
	private static String packageName(ClassName name){
		var dotted = name.dotted();
		return dotted.substring(0, dotted.lastIndexOf('.') + 1);
	}
}
