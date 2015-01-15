/*
 * Copyright 2014 Pivotal Software Inc. and contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springsource.loaded.support;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * This class encapsulates dependencies on Java 8 APIs (e.g. LambdaMetafactory).
 * 
 * @author Andy Clement
 * @since 1.2
 */
public class Java8 {

    /**
     * Notes:
     * 
     * Useful to have an example of how this code behaves. Here is a bit of code:
     * 
     * class basic.LambdaA {
     * interface Foo { int m(); }
     * static int run() {
     * Foo f = null;
     * f = () -> 77;
     * return f.m();
     * }
     * }
     * 
     * Here is a bootstrap method entry in the constant pool:
     * 
     * 0: #31 invokestatic java/lang/invoke/LambdaMetafactory.metafactory:
     * (Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;
     * Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;
     * Method arguments:
     * #32 ()I
     * #33 invokestatic basic/LambdaA.lambda$run$0:()I
     * #32 ()I
     * 
     * At the invokedynamic site:
     * bsmId = 0
     * nameAndDescriptor = m()Lbasic/LambdaA$Foo;
     * 
     * When invoking the metafactory bootstrap method the first two parameters are stacked by the VM automatically, namely the
     * MethodHandles$Lookup
     * instance (caller) and the first String (invokedName). What the VM actually sees is this:
     * 
     * metaFactory parameters:
     * 0:MethodHandles$Lookup caller = basic.LambdaA
     * 1:String invokedName = "m"
     * 2:MethodType invokedType = "()Foo"
     * 3:MethodType samMethodType = "()int"
     * 4:MethodHandle implMethod = (actually a DirectMethodHandle where memberName is
     * "basic.LambdaA.lambda$run$0()int/invokeStatic")
     * 5:MethodType instantiatedMethodType = "()int"
     * 
     * With all that information then the calls in this case are relatively straightforward:
     * CallSite callsite = LambdaMetafactory.metafactory(caller, invokedName, invokedType, samMethodType, implMethod,
     * instantiatedMethodType);
     * callsite.dynamicInvoker().invokeWithArguments((Object[])null);
     */

    /**
     * Programmatic emulation of INVOKEDYNAMIC so initialize the callsite via use of the bootstrap method then
     * invoke the result.
     * 
     * @param executorClass the executor that will contain the lambda function, null if not yet reloaded
     * @param handle bootstrap method handle
     * @param bsmArgs bootstrap method arguments
     * @param lookup The MethodHandles.Lookup object that can be used to find types
     * @param indyNameAndDescriptor Method name and descriptor at invokedynamic site
     * @param indyParams parameters when the invokedynamic call is made
     * @return the result of the invokedynamic call
     */
    public static Object emulateInvokeDynamic(Class<?> executorClass, Handle handle, Object[] bsmArgs, Object lookup,
            String indyNameAndDescriptor, Object[] indyParams) {
        try {
            CallSite callsite = callLambdaMetaFactory(bsmArgs, lookup, indyNameAndDescriptor, executorClass);
            return callsite.dynamicInvoker().invokeWithArguments(indyParams);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    // TODO [perf] How about a table of CallSites indexed by invokedynamic number through the class file. Computed on first reference but cleared on reload. Possibly extend this to all invoke types!

    // TODO [lambda] Need to handle altMetaFactory which is used when the lambdas are more 'complex' (e.g. Serializable)
    public static CallSite callLambdaMetaFactory(Object[] bsmArgs, Object lookup, String indyNameAndDescriptor,
            Class<?> executorClass) throws Exception {
        MethodHandles.Lookup caller = (MethodHandles.Lookup) lookup;

        ClassLoader callerLoader = caller.lookupClass().getClassLoader();

        int descriptorStart = indyNameAndDescriptor.indexOf('(');
        String invokedName = indyNameAndDescriptor.substring(0, descriptorStart);
        MethodType invokedType =
                MethodType.fromMethodDescriptorString(indyNameAndDescriptor.substring(descriptorStart), callerLoader);

        // Use bsmArgs to build the parameters 
        MethodType samMethodType = MethodType.fromMethodDescriptorString((((Type) bsmArgs[0]).getDescriptor()), callerLoader);
        String execClassName = executorClass.getName();
        Handle bsmArgsHandle = (Handle) bsmArgs[1];
        Class<?> lookupClass = caller.lookupClass();
        String owner = bsmArgsHandle.getOwner();
        if (!execClassName.startsWith(owner.replace('/', '.')) && bsmArgsHandle.getTag() != Opcodes.H_INVOKEINTERFACE) {
            executorClass = null;
            lookupClass = Class.forName(owner.replace('/', '.'));
        }
        String name = bsmArgsHandle.getName();
        String descriptor = bsmArgsHandle.getDesc();
        MethodType implMethodType = MethodType.fromMethodDescriptorString(descriptor, callerLoader);
        // Looking up the lambda$run method in the caller class (note the caller class is the executor, which gets us around the
        // problem of having to hack into LambdaMetafactory to intercept reflection)
        MethodHandle implMethod = null;
        switch (bsmArgsHandle.getTag()) {
        case Opcodes.H_INVOKESTATIC:
            implMethod = caller.in(lookupClass).findStatic(lookupClass, name, implMethodType);
            break;
        case Opcodes.H_INVOKESPECIAL:
            // If there is an executor, the lambda function is actually modified from 'private instance' to 'public static' so adjust lookup. The method 
            // will be static with a new leading parameter.
            if (executorClass == null) {
                // TODO is final parameter here correct?
                implMethod = caller.in(lookupClass).findSpecial(lookupClass, name, implMethodType, caller.lookupClass());
            } else {
                implMethod =
                        caller.in(lookupClass)
                                .findStatic(
                                        lookupClass,
                                        name,
                                        MethodType.fromMethodDescriptorString("(L" + owner + ";" + descriptor.substring(1),
                                                callerLoader));
            }
            break;
        case Opcodes.H_INVOKEVIRTUAL:
            // If there is an executor, the lambda function is actually modified from 'private instance' to 'public static' so adjust lookup. The method 
            // will be static with a new leading parameter.
            if (executorClass == null) {
                // TODO when can this scenario occur? Aren't we only here if reloading has happened?
                implMethod = caller.in(lookupClass).findVirtual(lookupClass, name, implMethodType);
            } else {
                implMethod =
                        caller.in(lookupClass)
                                .findStatic(
                                        lookupClass,
                                        name,
                                        MethodType.fromMethodDescriptorString("(L" + owner + ";" + descriptor.substring(1),
                                                callerLoader));
            }
            break;
        case Opcodes.H_INVOKEINTERFACE:
            Class<?> targetType = Class.forName(bsmArgsHandle.getOwner().replace('/', '.'), true, callerLoader);

            for (Method m : targetType.getMethods()) {
                if (org.objectweb.asm.Type.getMethodDescriptor(m).equals(bsmArgsHandle.getDesc())) {
                    implMethod = caller.unreflect(targetType.getMethod(name, m.getParameterTypes()));
                    break;
                }
            }
            break;
        default:
            throw new IllegalStateException("nyi " + bsmArgsHandle.getTag());
        }

        MethodType instantiatedMethodType =
                MethodType.fromMethodDescriptorString((((Type) bsmArgs[2]).getDescriptor()), callerLoader);

        return LambdaMetafactory.metafactory(caller, invokedName, invokedType, samMethodType, implMethod, instantiatedMethodType);
    }

    /**
     * The metafactory we are enhancing is responsible for generating the anonymous classes that will call the lambda methods in
     * our type
     * 
     * @param bytes the class bytes for the InnerClassLambdaMetaFactory that is going to be modified
     * @return the class bytes for the modified InnerClassLambdaMetaFactory
     */
    public static byte[] enhanceInnerClassLambdaMetaFactory(byte[] bytes) {
        // TODO Auto-generated method stub
        return null;
    }
}
