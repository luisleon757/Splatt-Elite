from markdown_pdf import MarkdownPdf, Section
import sys
import re

def main():
    md_file = 'GUIA_USO.md'
    pdf_file = 'GUIA_USO.pdf'
    
    with open(md_file, 'r', encoding='utf-8') as f:
        md_content = f.read()
        
    # Remove TOC block to avoid anchor link errors in markdown-pdf
    md_content = re.sub(r'## 📋 Tabla de Contenidos.*?\n---\n', '', md_content, flags=re.DOTALL)
    
    # Replace mermaid block with a simple text block because markdown-pdf might not render it correctly
    md_content = re.sub(r'```mermaid.*?```', '*(Diagrama de Flujo del Sistema)*', md_content, flags=re.DOTALL)

    pdf = MarkdownPdf(toc_level=2)
    pdf.add_section(Section(md_content))
    pdf.save(pdf_file)
    print(f"Generated {pdf_file}")

if __name__ == '__main__':
    main()
