"""Start only the demo service using the model section of the private backend config."""
from pathlib import Path
import os
import subprocess
import sys

import yaml

root = Path(__file__).resolve().parents[3]
config_path = Path(os.environ.get("MODEL_CONFIG_PATH", root / "backend/config/application.yaml"))
try:
    with config_path.open(encoding="utf-8") as stream:
        model = yaml.safe_load(stream)["brain"]["resources"]["model"]
    env = os.environ.copy()
    for variable, key in (("MODEL_BASE_URL", "base-url"), ("MODEL_API_KEY", "api-key"), ("MODEL_NAME", "model")):
        value = model[key]
        if not isinstance(value, str) or not value.strip():
            raise ValueError(f"Model configuration {key} is empty")
        env[variable] = value
except (OSError, KeyError, TypeError, ValueError, yaml.YAMLError) as error:
    print(f"Cannot read the required model configuration: {type(error).__name__}", file=sys.stderr)
    sys.exit(1)

java = str(Path(env["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")) if env.get("JAVA_HOME") else "java"
jar = Path(__file__).resolve().parent / "target/agui-validation-0.1.0-SNAPSHOT.jar"
sys.exit(subprocess.call([java, "-jar", str(jar)], cwd=jar.parent.parent, env=env))
