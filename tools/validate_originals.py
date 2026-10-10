# -*- coding: utf-8 -*-
"""Control test: do the ORIGINAL editor files validate against the shipped XSDs?"""
import glob, os, sys
from lxml import etree
XSD4 = r'E:\V4\vsq4.xsd'; XSD3 = r'E:\V4\vsq3.xsd'
s4 = etree.XMLSchema(etree.parse(XSD4)); s3 = etree.XMLSchema(etree.parse(XSD3))
for f in sorted(glob.glob(sys.argv[1] + r'\*.vsqx')):
    doc = etree.parse(f); root = etree.QName(doc.getroot()).localname
    sch = s4 if root == 'vsq4' else s3
    ok = sch.validate(doc)
    print('%-6s %-42s %s' % (root, os.path.basename(f)[:42], 'VALID' if ok else 'INVALID: ' + sch.error_log[0].message[:110]))
