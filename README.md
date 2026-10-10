# VRBoxSpatial

Protótipo Android para usar um celular em um VR Box/Cardboard com:

- câmera traseira como passthrough;
- imagem dividida para os dois olhos;
- janelas virtuais espaciais;
- rastreamento de rotação da cabeça pelo sensor do Android;
- rastreamento de mão com MediaPipe carregado pela internet;
- gesto de pinça para selecionar e arrastar janelas;
- ajustes de FOV, paralaxe e escala.

## Estado atual

Esta é uma versão MVP. As janelas ainda são painéis internos desenhados pelo app; não são apps Android reais nem um runtime OpenXR/Wolvic. O rastreamento é 3DoF por rotação, não ARCore 6DoF.

## APK

O workflow do GitHub Actions em `.github/workflows/build-apk.yml` compila automaticamente um APK debug em cada push para `main`.

Artefato esperado: `VRBoxSpatial-debug-apk`, contendo `app-debug.apk`.

## Uso

1. Instale o APK no Android.
2. Autorize a câmera.
3. Toque em **Iniciar câmera + VR**.
4. Coloque o celular no VR Box com a câmera traseira desobstruída.
5. Use **Recentrar** olhando para frente.
6. Ajuste FOV/paralaxe se necessário.

O MediaPipe precisa de internet na primeira execução porque os arquivos do modelo são carregados remotamente.
