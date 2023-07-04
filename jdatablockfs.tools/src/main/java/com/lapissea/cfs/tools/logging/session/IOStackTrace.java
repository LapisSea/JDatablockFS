package com.lapissea.cfs.tools.logging.session;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IOValue;

public class IOStackTrace extends IOInstance.Managed<IOStackTrace>{
	
	private interface Element extends IOInstance.Def<Element>{
		
		private static int length(String s){
			return (s == null)? 0 : s.length();
		}
//		static String toString(Element e){
//			int estimatedLength = length(moduleName) + 1
//			                      + length(moduleVersion) + 1
//			                      + declaringClass.length() + 1
//			                      + methodName.length() + 1
//			                      + Math.max(UNKNOWN_SOURCE.length(), length(fileName)) + 1
//			                      + 12;
//
//			StringBuilder sb = new StringBuilder(estimatedLength);
//			if(!dropClassLoaderName() && classLoaderName != null && !classLoaderName.isEmpty()){
//				sb.append(classLoaderName).append('/');
//			}
//
//			if(moduleName != null && !moduleName.isEmpty()){
//				sb.append(moduleName);
//				if(!dropModuleVersion() && moduleVersion != null && !moduleVersion.isEmpty()){
//					sb.append('@').append(moduleVersion);
//				}
//			}
//
//			if(sb.length()>0){
//				sb.append('/');
//			}
//
//			sb.append(declaringClass).append('.').append(methodName).append('(');
//			if(isNativeMethod()){
//				sb.append(NATIVE_METHOD);
//			}else if(fileName == null){
//				sb.append(UNKNOWN_SOURCE);
//			}else{
//				sb.append(fileName);
//				if(lineNumber>=0){
//					sb.append(':').append(lineNumber);
//				}
//			}
//			sb.append(')');
//
//			return sb.toString();
//		}
		
		@IOValue.Unsigned
		@IODependency.VirtualNumSize
		int moduleNameId();
		@IOValue.Unsigned
		@IODependency.VirtualNumSize
		int moduleVersionId();
		@IOValue.Unsigned
		@IODependency.VirtualNumSize
		int declaringClassId();
		@IOValue.Unsigned
		@IODependency.VirtualNumSize
		int methodNameId();
		@IOValue.Unsigned
		@IODependency.VirtualNumSize
		int fileNameId();
		@IODependency.VirtualNumSize
		int lineNumber();
	}
	
	
}
