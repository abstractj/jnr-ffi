/*
 * Copyright (C) 2008-2010 Wayne Meissner
 *
 * This file is part of the JNR project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jnr.ffi.provider.jffi;

import com.kenai.jffi.*;
import com.kenai.jffi.CallingConvention;
import com.kenai.jffi.Type;
import jnr.ffi.*;
import jnr.ffi.NativeType;
import jnr.ffi.annotations.*;
import jnr.ffi.mapper.*;
import jnr.ffi.util.Annotations;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.Buffer;
import java.util.*;

import static jnr.ffi.provider.jffi.AsmUtil.isDelegate;
import static jnr.ffi.provider.jffi.NumberUtil.sizeof;
import static jnr.ffi.util.Annotations.sortedAnnotationCollection;

final class InvokerUtil {

    public static boolean requiresErrno(Method method) {
        boolean saveError = true;
        for (Annotation a : method.getAnnotations()) {
            if (a instanceof IgnoreError) {
                saveError = false;
            } else if (a instanceof SaveError) {
                saveError = true;
            }
        }
        return saveError;
    }

    public static com.kenai.jffi.CallingConvention getCallingConvention(Map<LibraryOption, ?> libraryOptions) {
        Object convention = libraryOptions.get(LibraryOption.CallingConvention);

        // If someone passed in the jffi calling convention, just use it.
        if (convention instanceof com.kenai.jffi.CallingConvention) {
            return (com.kenai.jffi.CallingConvention) convention;
        }
        
        if (convention instanceof jnr.ffi.CallingConvention) switch ((jnr.ffi.CallingConvention) convention) {
            case DEFAULT:
                return com.kenai.jffi.CallingConvention.DEFAULT;
            case STDCALL:
                return com.kenai.jffi.CallingConvention.STDCALL;

        } else if (convention != null) {
            throw new IllegalArgumentException("unknown calling convention: " + convention);
        }

        return com.kenai.jffi.CallingConvention.DEFAULT;
    }

    public static boolean hasAnnotation(Collection<Annotation> annotations, Class<? extends Annotation> annotationClass) {
        for (Annotation a : annotations) {
            if (annotationClass.isInstance(a)) {
                return true;
            }
        }

        return false;
    }

    static final Map<jnr.ffi.NativeType, Type> jffiTypes;
    static {
        Map<jnr.ffi.NativeType, Type> m = new EnumMap<NativeType, Type>(jnr.ffi.NativeType.class);

        m.put(NativeType.VOID, Type.VOID);
        m.put(NativeType.SCHAR, Type.SCHAR);
        m.put(NativeType.UCHAR, Type.UCHAR);
        m.put(NativeType.SSHORT, Type.SSHORT);
        m.put(NativeType.USHORT, Type.USHORT);
        m.put(NativeType.SINT, Type.SINT);
        m.put(NativeType.UINT, Type.UINT);
        m.put(NativeType.SLONG, Type.SLONG);
        m.put(NativeType.ULONG, Type.ULONG);
        m.put(NativeType.SLONGLONG, Type.SLONG_LONG);
        m.put(NativeType.ULONGLONG, Type.ULONG_LONG);
        m.put(NativeType.FLOAT, Type.FLOAT);
        m.put(NativeType.DOUBLE, Type.DOUBLE);
        m.put(NativeType.ADDRESS, Type.POINTER);

        jffiTypes = Collections.unmodifiableMap(m);
    }

    static Type jffiType(jnr.ffi.NativeType jnrType) {
        Type jffiType = jffiTypes.get(jnrType);
        if (jffiType != null) {
            return jffiType;
        }

        throw new IllegalArgumentException("unsupported parameter type: " + jnrType);
    }

    static NativeType nativeType(jnr.ffi.Type jnrType) {
        return jnrType.getNativeType();
    }


    static Collection<Annotation> getAnnotations(jnr.ffi.mapper.FromNativeType fromNativeType) {
        return fromNativeType != null ? ConverterMetaData.getAnnotations(fromNativeType.getFromNativeConverter()) : Annotations.EMPTY_ANNOTATIONS;
    }

    static Collection<Annotation> getAnnotations(jnr.ffi.mapper.ToNativeType toNativeType) {
        return toNativeType != null ? ConverterMetaData.getAnnotations(toNativeType.getToNativeConverter()) : Annotations.EMPTY_ANNOTATIONS;
    }

    static ResultType getResultType(jnr.ffi.Runtime runtime, Class type, Collection<Annotation> annotations,
                                    FromNativeConverter fromNativeConverter, FromNativeContext fromNativeContext) {
        Collection<Annotation> converterAnnotations = ConverterMetaData.getAnnotations(fromNativeConverter);
        Collection<Annotation> allAnnotations = Annotations.mergeAnnotations(annotations, converterAnnotations);
        NativeType nativeType = getMethodResultNativeType(runtime,
                fromNativeConverter != null ? fromNativeConverter.nativeType() : type, allAnnotations);
        boolean useContext = fromNativeConverter != null && !hasAnnotation(converterAnnotations, FromNativeConverter.NoContext.class);
        return new ResultType(type, nativeType, allAnnotations, fromNativeConverter, useContext ? fromNativeContext : null);
    }

    static ResultType getResultType(jnr.ffi.Runtime runtime, Class type, Collection<Annotation> annotations, 
                                    jnr.ffi.mapper.FromNativeType fromNativeType, FromNativeContext fromNativeContext) {
        Collection<Annotation> converterAnnotations = getAnnotations(fromNativeType);
        Collection<Annotation> allAnnotations = Annotations.mergeAnnotations(annotations, converterAnnotations);
        FromNativeConverter fromNativeConverter = fromNativeType != null ? fromNativeType.getFromNativeConverter() : null;
        NativeType nativeType = getMethodResultNativeType(runtime,
                fromNativeConverter != null ? fromNativeConverter.nativeType() : type, allAnnotations);
        boolean useContext = fromNativeConverter != null && !hasAnnotation(converterAnnotations, FromNativeConverter.NoContext.class);
        return new ResultType(type, nativeType, allAnnotations, fromNativeConverter, useContext ? fromNativeContext : null);
    }

    private static ParameterType getParameterType(jnr.ffi.Runtime runtime, Class type, Collection<Annotation> annotations,
                                          ToNativeConverter toNativeConverter, ToNativeContext toNativeContext) {
        NativeType nativeType = getMethodParameterNativeType(runtime,
                toNativeConverter != null ? toNativeConverter.nativeType() : type, annotations);
        return new ParameterType(type, nativeType, annotations, toNativeConverter, toNativeContext);
    }

    private static ParameterType getParameterType(jnr.ffi.Runtime runtime, Class type, Collection<Annotation> annotations,
                                                  jnr.ffi.mapper.ToNativeType toNativeType, ToNativeContext toNativeContext) {
        ToNativeConverter toNativeConverter = toNativeType != null ? toNativeType.getToNativeConverter() : null;
        NativeType nativeType = getMethodParameterNativeType(runtime,
                toNativeConverter != null ? toNativeConverter.nativeType() : type, annotations);
        return new ParameterType(type, nativeType, annotations, toNativeConverter, toNativeContext);
    }

    static ParameterType[] getParameterTypes(jnr.ffi.Runtime runtime, SignatureTypeMapper typeMapper,
                                             Method m) {
        final Class[] javaParameterTypes = m.getParameterTypes();
        final Annotation[][] parameterAnnotations = m.getParameterAnnotations();
        ParameterType[] parameterTypes = new ParameterType[javaParameterTypes.length];

        for (int pidx = 0; pidx < javaParameterTypes.length; ++pidx) {
            Collection<Annotation> annotations = Annotations.sortedAnnotationCollection(parameterAnnotations[pidx]);
            ToNativeContext toNativeContext = new MethodParameterContext(runtime, m, pidx, annotations);
            SignatureType signatureType = DefaultSignatureType.create(javaParameterTypes[pidx], toNativeContext);
            jnr.ffi.mapper.ToNativeType toNativeType = typeMapper.getToNativeType(signatureType, toNativeContext);
            ToNativeConverter toNativeConverter = toNativeType != null ? toNativeType.getToNativeConverter() : null;
            Collection<Annotation> converterAnnotations = ConverterMetaData.getAnnotations(toNativeConverter);
            Collection<Annotation> allAnnotations = Annotations.mergeAnnotations(annotations, converterAnnotations);

            boolean contextRequired = toNativeConverter != null && !hasAnnotation(converterAnnotations, ToNativeConverter.NoContext.class);
            parameterTypes[pidx] = getParameterType(runtime, javaParameterTypes[pidx],
                    allAnnotations, toNativeConverter, contextRequired ? toNativeContext : null);
        }

        return parameterTypes;
    }

    static CallContext getCallContext(SigType resultType, SigType[] parameterTypes, com.kenai.jffi.CallingConvention convention, boolean requiresErrno) {
        com.kenai.jffi.Type[] nativeParamTypes = new com.kenai.jffi.Type[parameterTypes.length];

        for (int i = 0; i < nativeParamTypes.length; ++i) {
            nativeParamTypes[i] = jffiType(parameterTypes[i].nativeType);
        }

        return CallContextCache.getInstance().getCallContext(jffiType(resultType.nativeType),
                nativeParamTypes, convention, requiresErrno);
    }

    public static com.kenai.jffi.CallingConvention getNativeCallingConvention(Method m) {
        if (m.isAnnotationPresent(StdCall.class) || m.getDeclaringClass().isAnnotationPresent(StdCall.class)) {
            return CallingConvention.STDCALL;
        }

        return CallingConvention.DEFAULT;
    }

    
    static NativeType getMethodParameterNativeType(jnr.ffi.Runtime runtime, Class parameterClass, Collection<Annotation> annotations) {
        return Types.getType(runtime, parameterClass, annotations).getNativeType();
    }

    static NativeType getMethodResultNativeType(jnr.ffi.Runtime runtime, Class resultClass, Collection<Annotation> annotations) {
        return Types.getType(runtime, resultClass, annotations).getNativeType();
    }


    static void generateFunctionInvocation(NativeRuntime runtime, AsmBuilder builder, Method m, long functionAddress, CallingConvention callingConvention, boolean saveErrno, SignatureTypeMapper typeMapper, MethodGenerator[] generators) {
        FromNativeContext resultContext = new MethodResultContext(runtime, m);
        SignatureType signatureType = DefaultSignatureType.create(m.getReturnType(), resultContext);
        ResultType resultType = getResultType(runtime, m.getReturnType(),
                resultContext.getAnnotations(), typeMapper.getFromNativeType(signatureType, resultContext),
                resultContext);

        ParameterType[] parameterTypes = getParameterTypes(runtime, typeMapper, m);

        Function function = getFunction(functionAddress,
                resultType, parameterTypes, saveErrno, callingConvention);

        for (MethodGenerator g : generators) {
            if (g.isSupported(resultType, parameterTypes, callingConvention)) {
                g.generate(builder, m.getName(), function, resultType, parameterTypes, !saveErrno);
                break;
            }
        }
    }


    private static Function getFunction(long address, ResultType resultType, ParameterType[] parameterTypes,
                                        boolean requiresErrno, CallingConvention convention) {
        return new Function(address, getCallContext(resultType, parameterTypes, convention, requiresErrno));
    }
}
