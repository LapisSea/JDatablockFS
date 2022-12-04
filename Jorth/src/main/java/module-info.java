module Jorth {
	requires jlapisutil;
	requires org.objectweb.asm;
	requires org.objectweb.asm.util;
	
	exports com.lapissea.jorth;
	exports com.lapissea.jorth.v2;
	exports com.lapissea.jorth.lang to jlapisutil;
	opens com.lapissea.jorth.lang to jlapisutil;
}
