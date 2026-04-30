# Hermes Analyzer - Termux 한 번에 설치

## 방법 A: 원클릭 설치

```bash
pkg update -y && pkg install -y openjdk-17 gradle git curl unzip aapt2 apksigner zipalign && \
cd $HOME && curl -L -o hermes.tar.gz "https://raw.githubusercontent.com/사용자명/hermes-analyzer/main/hermes-apk.tar.gz" && \
tar -xzf hermes.tar.gz && cd hermes-apk && bash termux-build.sh
```

## 방법 B: 파일 직접 복사

```bash
cp /sdcard/Download/hermes-analyzer-termux.tar.gz $HOME/
cd $HOME
tar -xzf hermes-analyzer-termux.tar.gz
cd hermes-apk
bash termux-build.sh
```

## 방법 C: SSH 연결

### 핸드폰에서:
```bash
pkg install -y openssh
sshd
ifconfig
```

### 이 샌드박스에서:
```bash
ssh -p 8022 u0_aXXX@핸드폰_IP
cd hermes-apk && bash termux-build.sh
```
