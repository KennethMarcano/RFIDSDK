# Dependências Pi4J v2

Para usar o controle GPIO com Pi4J v2 no Raspberry Pi, você precisa adicionar as seguintes bibliotecas:

## Opção 1: Maven (Recomendado)

Se você usa Maven, o arquivo `pom.xml` já foi criado com as dependências necessárias.

Execute:
```bash
mvn clean install
```

## Opção 2: Download Manual

Se você não usa Maven, baixe os seguintes JARs e adicione à pasta `libs/`:

1. **pi4j-core-2.3.0.jar**
   - Download: https://repo1.maven.org/maven2/com/pi4j/pi4j-core/2.3.0/

2. **pi4j-plugin-pigpio-2.3.0.jar**
   - Download: https://repo1.maven.org/maven2/com/pi4j/pi4j-plugin-pigpio/2.3.0/

3. **pi4j-plugin-linuxfs-2.3.0.jar** (fallback)
   - Download: https://repo1.maven.org/maven2/com/pi4j/pi4j-plugin-linuxfs/2.3.0/

## Requisitos no Raspberry Pi

Para usar o provider PIGPIO, você precisa ter o `pigpiod` rodando:

```bash
sudo systemctl enable pigpiod
sudo systemctl start pigpiod
```

Ou iniciar manualmente:
```bash
sudo pigpiod
```

## Nota

O código usa o provider "pigpio-digital-output" por padrão. Se não estiver disponível, o Pi4J tentará usar outros providers automaticamente.
