/*
 * Copyright (c) 2012 Aleksey Shipilev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.shipilev.concurrent.torture;

import net.shipilev.concurrent.torture.tests.OneActorOneObserverTest;
import net.shipilev.concurrent.torture.tests.TwoActorsOneArbiterTest;
import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.scanners.TypeAnnotationsScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;

import javax.xml.bind.JAXBException;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.Comparator;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

public class ForkedMain {

    public static void main(String[] args) throws ExecutionException, InterruptedException, IOException, IllegalAccessException, InstantiationException, JAXBException {
        Options opts = new Options(args);
        if (!opts.parse()) {
            System.exit(1);
        }

        Runner r = new Runner(opts);

        for (Class<? extends OneActorOneObserverTest> test : filterTests(opts.getTestFilter(), OneActorOneObserverTest.class)) {
            OneActorOneObserverTest<?> instance = test.newInstance();
            r.run(instance);
        }

        for (Class<? extends TwoActorsOneArbiterTest> test : filterTests(opts.getTestFilter(), TwoActorsOneArbiterTest.class)) {
            TwoActorsOneArbiterTest<?> instance = test.newInstance();
            r.run(instance);
        }

        r.close();
    }

    private static <T> SortedSet<Class<? extends T>> filterTests(final String filter, Class<T> klass) {
        // God I miss both diamonds and lambdas here.

        Pattern pattern = Pattern.compile(filter);

        Reflections r = new Reflections(
                new ConfigurationBuilder()
                        .filterInputsBy(new FilterBuilder().include("net.shipilev.concurrent.torture.*"))
                        .setUrls(ClasspathHelper.forClassLoader())
                        .setScanners(new SubTypesScanner(), new TypeAnnotationsScanner()));

        SortedSet<Class<? extends T>> s = new TreeSet<Class<? extends T>>(new Comparator<Class<? extends T>>() {
            @Override
            public int compare(Class<? extends T> o1, Class<? extends T> o2) {
                return o1.getName().compareTo(o2.getName());
            }
        });

        for (Class<? extends T> k : r.getSubTypesOf(klass)) {
            if (!pattern.matcher(k.getName()).matches()) {
                continue;
            }
            if (Modifier.isAbstract(k.getModifiers())) {
                continue;
            }
            s.add(k);
        }

        return s;
    }


}
