import type { NextConfig } from "next";

process.env.COPILOTKIT_TELEMETRY_DISABLED = "true";
process.env.NEXT_TELEMETRY_DISABLED = "1";

const config: NextConfig = {};
export default config;
