/*
 * (C) Copyright 2006-2008 Nuxeo SAS (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Nuxeo - initial API and implementation
 *
 * $Id$
 */

package org.nuxeo.ecm.platform.importer.tests;

import org.junit.Test;
import org.nuxeo.ecm.platform.importer.random.DictionaryHolder;
import org.nuxeo.ecm.platform.importer.random.HunspellDictionaryHolder;
import org.nuxeo.ecm.platform.importer.random.RandomTextGenerator;

public class TestWordGen {

    @Test
    public void testWordGen() throws Exception {
        long t0 = System.currentTimeMillis();
        DictionaryHolder dic = new HunspellDictionaryHolder("fr_FR.dic");
        RandomTextGenerator gen = new RandomTextGenerator(dic);
        long t1 = System.currentTimeMillis();
        System.out.println("loading dico :" + (t1 - t0) + "ms");
        System.out.println("prefilling cache ...");
        gen.prefilCache();
        long t2 = System.currentTimeMillis();
        System.out.println("cache prefill time :" + ((t2 - t1) / 1000) + "s");

        int nbFiles = 2000;
        for (int i = 0; i < nbFiles; i++) {
            gen.getRandomText();
        }
        long t3 = System.currentTimeMillis();
        if (t3 == t2) {
            t3++;
        }

        System.out.println("generated files in " + ((t3 - t2) / 1000) + "s");
        float rate = nbFiles * 1000 / (t3 - t2);
        System.out.println(rate + " files/s");
    }

    @Test
    public void testTextGen() throws Exception {
        long t0 = System.currentTimeMillis();
        DictionaryHolder dic = new HunspellDictionaryHolder("fr_FR.dic");
        RandomTextGenerator gen = new RandomTextGenerator(dic);
        long t1 = System.currentTimeMillis();
        System.out.println("loading dico :" + (t1 - t0) + "ms");
        System.out.println("prefilling cache ...");
        gen.prefilCache();
        long t2 = System.currentTimeMillis();
        System.out.println("cache prefill time :" + ((t2 - t1) / 1000) + "s");

        String txt1000 = gen.getRandomText(1000);
        String txt500 = gen.getRandomText(500);
        String txt50 = gen.getRandomText(50);
        System.out.println("txt1000  " + txt1000.length());
        System.out.println("txt500  " + txt500.length());
        System.out.println("txt50  " + txt50.length());
    }
}
