package com.icbc.changzhou.ipam;

/** Shared password policy for every password-setting entry point. */
final class PasswordPolicy {
  private PasswordPolicy() {}

  static boolean isCompliant(String value) {
    return validationMessage(value) == null;
  }

  static String validationMessage(String value) {
    if (value == null) return "密码长度至少 8 位。";
    ListFlags flags = new ListFlags();
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      if (ch >= 'A' && ch <= 'Z') flags.upper = true;
      else if (ch >= 'a' && ch <= 'z') flags.lower = true;
      else if (ch >= '0' && ch <= '9') flags.digit = true;
    }
    StringBuilder message = new StringBuilder();
    if (value.length() < 8) message.append("密码长度至少 8 位");
    if (value.length() > 72) append(message, "密码长度不能超过 72 位");
    if (!flags.upper) append(message, "必须包含 ASCII 大写字母 A-Z");
    if (!flags.lower) append(message, "必须包含 ASCII 小写字母 a-z");
    if (!flags.digit) append(message, "必须包含数字 0-9");
    return message.length() == 0 ? null : message.append('。').toString();
  }

  private static void append(StringBuilder message, String part) {
    if (message.length() > 0) message.append('；');
    message.append(part);
  }

  private static final class ListFlags {
    boolean upper;
    boolean lower;
    boolean digit;
  }
}
