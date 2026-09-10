package com.lapissea.jorth.repro;

import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.JType;
import org.testng.annotations.Test;

import java.lang.reflect.ParameterizedType;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ReproJorthWildcardAsGenericTests{
	
	public List<?> unbounded;
	
	@Test
	void unboundedWildcardResolvesToObject(){
		assertThat(JType.WILDCARD.asGeneric()).isEqualTo(GenericType.OBJECT);
		assertThat(JType.WILDCARD.withoutArgs()).isEqualTo(GenericType.OBJECT);
		assertThat(JType.WILDCARD.jvmSignatureStr()).isEqualTo("*");
		assertThat(JType.WILDCARD.jvmSignatureLen()).isEqualTo(1);
		assertThat(JType.WILDCARD.jvmDescriptorStr()).isEqualTo("Ljava/lang/Object;");
		assertThat(JType.WILDCARD.jvmDescriptorLen()).isEqualTo("Ljava/lang/Object;".length());
	}
	
	@Test
	void reflectedUnboundedWildcardMatchesExplicitWildcard() throws Exception{
		var fieldType = (ParameterizedType)getClass().getField("unbounded").getGenericType();
		var reflected = JType.of(fieldType.getActualTypeArguments()[0]);
		assertThat(reflected.asGeneric()).isEqualTo(JType.WILDCARD.asGeneric());
		assertThat(reflected.jvmSignatureStr()).isEqualTo(JType.WILDCARD.jvmSignatureStr());
		assertThat(reflected.jvmDescriptorStr()).isEqualTo(JType.WILDCARD.jvmDescriptorStr());
	}
	
	@Test
	void boundedWildcardsRetainExistingResolution(){
		assertThat(JType.upper(Number.class).asGeneric()).isEqualTo(GenericType.of(Number.class));
		assertThat(JType.lower(String.class).asGeneric()).isEqualTo(GenericType.STRING);
		assertThat(JType.upper(Number.class).jvmSignatureStr()).isEqualTo("+Ljava/lang/Number;");
		assertThat(JType.lower(String.class).jvmSignatureStr()).isEqualTo("-Ljava/lang/String;");
	}
	
	@Test
	void wildcardTypeArgumentKeepsWildcardSignature(){
		var type = GenericType.of(List.class).withArgs(List.of(JType.WILDCARD));
		assertThat(type.jvmSignatureStr()).isEqualTo("Ljava/util/List<*>;");
		assertThat(type.jvmDescriptorStr()).isEqualTo("Ljava/util/List;");
	}
}
