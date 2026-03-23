"""Module lifecycle manager.

Supports dynamic module registration and provides health-check utilities.
"""
import logging
from typing import Dict, List, Optional, Type

from modules import BaseModule

logger = logging.getLogger(__name__)


class ModuleManager:
    """Manages registration, lookup, and health of feature modules."""

    def __init__(self) -> None:
        self._modules: Dict[str, BaseModule] = {}

    # ------------------------------------------------------------------
    # Registration
    # ------------------------------------------------------------------

    def register(self, module: BaseModule) -> None:
        """Register a module instance.

        Args:
            module: An instance of a :class:`BaseModule` subclass.
        """
        if module.name in self._modules:
            logger.warning("Module '%s' is already registered; overwriting.", module.name)
        self._modules[module.name] = module
        logger.info("Module registered: %s", module.name)

    def unregister(self, name: str) -> bool:
        """Unregister a module by name.

        Returns:
            True if the module was found and removed, False otherwise.
        """
        if name in self._modules:
            del self._modules[name]
            logger.info("Module unregistered: %s", name)
            return True
        logger.warning("Tried to unregister unknown module: %s", name)
        return False

    # ------------------------------------------------------------------
    # Lookup
    # ------------------------------------------------------------------

    def get(self, name: str) -> Optional[BaseModule]:
        """Return the module with the given name, or None."""
        return self._modules.get(name)

    def all_modules(self) -> List[BaseModule]:
        """Return all registered modules."""
        return list(self._modules.values())

    def list_names(self) -> List[str]:
        """Return the names of all registered modules."""
        return list(self._modules.keys())

    # ------------------------------------------------------------------
    # Health
    # ------------------------------------------------------------------

    def health_report(self) -> Dict[str, bool]:
        """Return a dict mapping each module name to its health status."""
        return {name: mod.health_check() for name, mod in self._modules.items()}
