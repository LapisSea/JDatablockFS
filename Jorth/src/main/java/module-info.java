module Jorth {
	requires jlapisutil;
	requires org.objectweb.asm;
	requires org.objectweb.asm.util;
	
	exports com.lapissea.jorth;
	exports com.lapissea.jorth.exceptions;
	exports com.lapissea.jorth.lang;
	exports com.lapissea.jorth.lang.type;
	
	opens com.lapissea.jorth to jlapisutil;
	opens com.lapissea.jorth.lang to jlapisutil;
	opens com.lapissea.jorth.lang.type to jlapisutil;
	opens com.lapissea.jorth.exceptions to jlapisutil;
}
