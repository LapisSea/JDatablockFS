module Jorth {
	requires jlapisutil;
	requires org.objectweb.asm;
	requires org.objectweb.asm.util;
	
	exports com.lapissea.jorth;
	exports com.lapissea.jorth.exceptions;
	
	opens com.lapissea.jorth to jlapisutil;
	opens com.lapissea.jorth.lang to jlapisutil;
	opens com.lapissea.jorth.lang.type to jlapisutil;
	opens com.lapissea.jorth.lang.info to jlapisutil;
	opens com.lapissea.jorth.exceptions to jlapisutil;
}
